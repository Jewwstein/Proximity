package com.echosight.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Log;

import com.google.ar.core.Pose;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Private photo atlas: real frames + ARCore world position / viewing direction.
 * NOT photogrammetry, a 3D model, or an accurate localization system in darkness.
 * Photographs remain on device; only a few decodes are cached at a time.
 */
public final class PhotoAtlas {
    private static final String TAG="EchoSight-Photos";
    public static final int MAX_FRAMES=240;
    private final File directory, indexFile;
    private final ArrayList<Entry> entries=new ArrayList<>();
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private volatile int written=0;
    public static final class Entry {
        public final int number;
        public final float[] position,rotation;
        Entry(int n,float[] pos,float[] rot){
            number=n;position=pos;rotation=rot;
        }
    }
    public static final class Choice {
        public final Entry entry;
        public final float score,distance,angle;
        Choice(Entry e,float score,float distance,float angle){
            entry=e;this.score=score;this.distance=distance;this.angle=angle;
        }
    }
    public PhotoAtlas(Context context) {
        directory=new File(context.getFilesDir(),"echosight-photo-atlas");
        indexFile=new File(directory,"frames.v1");
        if(!directory.exists())directory.mkdirs();
        load();
    }
    public synchronized void reset() {
        entries.clear();written=0;
        for(File f:directory.listFiles()==null?new File[0]:directory.listFiles()){
            if(!f.isDirectory())f.delete();
        }
    }
    public synchronized int count(){return entries.size();}
    public int savedCount(){return written;}
    public synchronized List<Entry> snapshot(){return new ArrayList<>(entries);}
    public boolean hasPhotos(){return count()>0;}
    private File imageFile(int number){return new File(directory,String.format(java.util.Locale.US,"frame_%04d.jpg",number));}
    /**
     * Called only for sufficiently distinct poses. Input is real camera
     * framebuffer, upright, without any UI overlays.
     */
    public synchronized boolean add(Bitmap source,Pose pose) {
        if(source==null||pose==null || entries.size()>=MAX_FRAMES){
            if(source!=null)source.recycle();return false;
        }
        float[] p=pose.getTranslation().clone();
        float[] q=pose.getRotationQuaternion().clone();
        if(!entries.isEmpty()){
            Entry last=entries.get(entries.size()-1);
            float d=(float)Math.sqrt((p[0]-last.position[0])*(p[0]-last.position[0])+
                    (p[1]-last.position[1])*(p[1]-last.position[1])+
                    (p[2]-last.position[2])*(p[2]-last.position[2]));
            float angle=rotationDifference(last.rotation,q);
            // Prefer distinct positions or turn angles, not repeated duplicates.
            if(d<.26f && angle<.30f){source.recycle();return false;}
        }
        final Entry e=new Entry(entries.size(),p,q);
        entries.add(e);
        writer.execute(()->{
            try{
                Bitmap reduced=source;
                int longest=Math.max(source.getWidth(),source.getHeight());
                if(longest>800){
                    float scale=800f/longest;
                    reduced=Bitmap.createScaledBitmap(source,
                        Math.max(2,Math.round(source.getWidth()*scale)),
                        Math.max(2,Math.round(source.getHeight()*scale)),true);
                }
                try(FileOutputStream output=new FileOutputStream(imageFile(e.number))){
                    reduced.compress(Bitmap.CompressFormat.JPEG,83,output);
                }
                if(reduced!=source)reduced.recycle();
                synchronized(PhotoAtlas.this){written++;writeIndex();}
            }catch(Throwable err){Log.w(TAG,"Frame save failed",err);}
            finally{source.recycle();}
        });
        return true;
    }
    public synchronized boolean save(){
        // Index is also saved after each frame, but this makes explicit SAVE
        // meaningful after a scan. The photo writer remains ordered.
        writer.execute(this::writeIndex);
        return !entries.isEmpty();
    }
    private synchronized void writeIndex(){
        if(!directory.exists())directory.mkdirs();
        File temporary=new File(directory,"frames.part");
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))){
            out.writeInt(0x50484F54);out.writeInt(1);
            out.writeInt(entries.size());
            for(Entry e:entries){
                out.writeInt(e.number);
                for(float v:e.position)out.writeFloat(v);
                for(float v:e.rotation)out.writeFloat(v);
            }
            out.flush();
            if(indexFile.exists()&&!indexFile.delete())return;
            if(!temporary.renameTo(indexFile))Log.w(TAG,"Could not commit photo index");
        }catch(IOException error){Log.w(TAG,"Index save failed",error);}
    }
    public synchronized boolean load(){
        if(!indexFile.exists())return false;
        ArrayList<Entry> list=new ArrayList<>();
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(indexFile)))){
            if(in.readInt()!=0x50484F54 || in.readInt()!=1)return false;
            int length=in.readInt();
            if(length<0||length>MAX_FRAMES)return false;
            for(int i=0;i<length;i++){
                int number=in.readInt();
                float[] pos=new float[3],rot=new float[4];
                for(int k=0;k<3;k++)pos[k]=in.readFloat();
                for(int k=0;k<4;k++)rot[k]=in.readFloat();
                if(imageFile(number).exists())list.add(new Entry(number,pos,rot));
            }
        }catch(Exception e){Log.w(TAG,"Could not open photo map",e);return false;}
        entries.clear();entries.addAll(list);written=list.size();
        return !entries.isEmpty();
    }
    public Bitmap image(Entry e){
        if(e==null)return null;
        File f=imageFile(e.number);
        if(!f.exists())return null;
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inPreferredConfig=Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(),options);
    }
    private static float rotationDifference(float[] a,float[] b){
        float dot=Math.abs(a[0]*b[0]+a[1]*b[1]+a[2]*b[2]+a[3]*b[3]);
        return (float)(2*Math.acos(Math.min(1,Math.max(-1,dot))));
    }
    private static float[] direction(float[] q){
        // Camera looks along local -Z. Quaternion rotates camera to AR world.
        float x=q[0],y=q[1],z=q[2],w=q[3];
        return new float[]{-2*(x*z+y*w),-2*(y*z-x*w),-(1-2*(x*x+y*y))};
    }
    public synchronized Choice choose(float[] pos,float[] direction){
        Choice best=null;
        for(Entry e:entries){
            if(!imageFile(e.number).exists())continue;
            float dx=pos[0]-e.position[0],dy=pos[1]-e.position[1],dz=pos[2]-e.position[2];
            float distance=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
            float[] look=direction(e.rotation);
            float dot=look[0]*direction[0]+look[1]*direction[1]+look[2]*direction[2];
            float angle=(float)Math.acos(Math.max(-1,Math.min(1,dot)));
            float score=distance*1.18f+angle*1.45f;
            if(best==null||score<best.score)
                best=new Choice(e,score,distance,angle);
        }
        // Do not show unrelated real-world images from another room when pose
        // estimate moves too far away. Fall back to dark screen with warning.
        if(best==null||best.distance>2.1f||best.angle>1.0f)return null;
        return best;
    }
}
