package com.echosight.app;

import com.google.ar.core.Pose;
import android.content.Context;
import java.io.*;
import java.util.*;

/** Point samples are ARCore world coordinates in meters, relative to THIS scan session. */
public final class SpatialMap {
    public static final int MAX_POINTS=68000;
    private final ArrayList<float[]> locations=new ArrayList<>();
    private final HashSet<Long> voxels=new HashSet<>();
    private float[] location=new float[]{0f,0f,0f};
    private float[] rotation=new float[]{0f,0f,0f,1f};
    private final Context context;
    private final File file;
    private int capturedFrames=0;
    private String description="No map yet";
    public SpatialMap(Context c){
        context=c.getApplicationContext();
        file=new File(c.getFilesDir(),"echosight-spatial-map-v1.bin");
    }
    private static int cell(float n){return Math.round(n*11.0f);}
    private static long hash(float x,float y,float z){
        long a=cell(x)+1048576L,b=cell(y)+1048576L,c=cell(z)+1048576L;
        return (a*73856093L)^(b*19349663L)^(c*83492791L);
    }
    public synchronized void add(float x,float y,float z){
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))return;
        if(locations.size()>=MAX_POINTS)return;
        if(Math.abs(x)>60||Math.abs(y)>20||Math.abs(z)>60)return;
        long k=hash(x,y,z);
        if(!voxels.add(k))return;
        locations.add(new float[]{x,y,z});
    }
    public synchronized void rememberPose(Pose p){
        if(p==null)return;
        location=p.getTranslation().clone();
        rotation=p.getRotationQuaternion().clone();
        capturedFrames++;
    }
    public synchronized int count(){return locations.size();}
    public synchronized int frames(){return capturedFrames;}
    public synchronized float[] position(){return location.clone();}
    public synchronized float[] orientation(){return rotation.clone();}
    public synchronized float[] snapshot(){
        float[] all=new float[locations.size()*3];int offset=0;
        for(float[] v:locations){
            all[offset++]=v[0];all[offset++]=v[1];all[offset++]=v[2];
        }
        return all;
    }
    public synchronized boolean exists(){return file.exists()&&file.length()>40;}
    public synchronized String description(){return description;}
    public synchronized void clear(){
        locations.clear();voxels.clear();capturedFrames=0;
        location=new float[]{0,0,0};rotation=new float[]{0,0,0,1};
        description="No map yet";
    }
    public synchronized void delete(){
        clear();file.delete();
    }
    public synchronized boolean save(){
        if(locations.size()<35)return false;
        File temp=new File(context.getFilesDir(),"echosight-tmp.bin");
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))){
            out.writeInt(0x4543484F);out.writeInt(1);
            out.writeInt(locations.size());
            for(float v:location)out.writeFloat(v);
            for(float v:rotation)out.writeFloat(v);
            for(float[] p:locations){
                out.writeFloat(p[0]);out.writeFloat(p[1]);out.writeFloat(p[2]);
            }
        }catch(IOException e){temp.delete();return false;}
        if(file.exists()&&!file.delete())return false;
        boolean okay=temp.renameTo(file);
        if(okay)description=locations.size()+" mapped points saved";
        return okay;
    }
    public synchronized boolean load(){
        if(!exists())return false;
        ArrayList<float[]> incoming=new ArrayList<>();
        float[] pos=new float[3],quat=new float[4];
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            if(in.readInt()!=0x4543484F||in.readInt()!=1)return false;
            int length=in.readInt();
            if(length<35||length>MAX_POINTS)return false;
            for(int i=0;i<3;i++)pos[i]=in.readFloat();
            for(int i=0;i<4;i++)quat[i]=in.readFloat();
            for(int i=0;i<length;i++){
                float x=in.readFloat(),y=in.readFloat(),z=in.readFloat();
                if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))return false;
                incoming.add(new float[]{x,y,z});
            }
        }catch(IOException e){return false;}
        clear();location=pos;rotation=quat;
        for(float[] p:incoming)add(p[0],p[1],p[2]);
        description=locations.size()+" points loaded · saved viewpoint";
        return true;
    }
}
