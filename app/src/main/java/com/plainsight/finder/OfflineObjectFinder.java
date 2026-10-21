package com.plainsight.finder;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.RectF;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class OfflineObjectFinder implements AutoCloseable {
    public static final int SIZE=320;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final List<String> labels=new ArrayList<>();
    public static final class Match {
        public final RectF bounds;
        public final String label;
        public final float score;
        public Match(RectF bounds,String label,float score){this.bounds=bounds;this.label=label;this.score=score;}
    }
    public OfflineObjectFinder(Context context) throws Exception {
        JSONArray a;
        try(InputStream stream=context.getAssets().open("categories.json")){
            byte[] data=new byte[stream.available()];
            int read=0;while(read<data.length){int n=stream.read(data,read,data.length-read);if(n<0)break;read+=n;}
            a=new JSONArray(new String(data,java.nio.charset.StandardCharsets.UTF_8));
        }
        for(int i=0;i<a.length();i++)labels.add(a.getString(i).toLowerCase(Locale.ROOT));
        File path=new File(context.getFilesDir(),"plainsight-yoloe.onnx");
        if(!path.exists() || path.length()<100000){
            try(InputStream in=context.getAssets().open("plainsight-yoloe.onnx");
                FileOutputStream out=new FileOutputStream(path)){
                byte[] buf=new byte[65536];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
        }
        env=OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts=new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session=env.createSession(path.getAbsolutePath(),opts);
        inputName=session.getInputNames().iterator().next();
    }
    public boolean supports(String target) {
        return !candidateLabels(target).isEmpty();
    }
    private List<Integer> candidateLabels(String term) {
        String q=term.toLowerCase(Locale.ROOT)
            .replaceFirst("^(please\\s+)?(find|locate|show me|where is|look for)\\s+","")
            .replaceFirst("^(my|the|a|an)\\s+","").trim();
        ArrayList<Integer> ids=new ArrayList<>();
        if(q.isEmpty())return ids;
        for(int i=0;i<labels.size();i++){
            String name=labels.get(i);
            if(name.equals(q) || name.contains(q) || q.contains(name))
                ids.add(i);
        }
        if(ids.isEmpty()) {
            String singular=q.replaceFirst("s$","");
            for(int i=0;i<labels.size();i++)
                if(labels.get(i).contains(singular))ids.add(i);
        }
        return ids;
    }
    private static float clamp(float x){return Math.min(1,Math.max(0,x));}
    private Match parse(float[][][] arr,List<Integer> wanted,String requested) {
        if(arr==null||arr.length==0)return null;
        float[][] data=arr[0];if(data.length==0)return null;
        float max=(requested.toLowerCase(Locale.ROOT).contains("straw") || requested.toLowerCase(Locale.ROOT).contains("key"))?.045f:.14f;Match best=null;
        // YOLO26 NMS-free format [1,300,38]: [x1,y1,x2,y2,score,class] + mask info.
        if(data[0].length>=6&&data[0].length<=64&&data.length>=10){
            for(float[] row:data){
                int cls=Math.round(row[5]);float score=row[4];
                if(cls<0||cls>=labels.size()||!wanted.contains(cls)||score<max)continue;
                float x0=row[0]/SIZE,y0=row[1]/SIZE,x1=row[2]/SIZE,y1=row[3]/SIZE;
                if(x1<=x0||y1<=y0)continue;
                max=score;
                best=new Match(new RectF(clamp(x0),clamp(y0),clamp(x1),clamp(y1)),labels.get(cls),score);
            }
            return best;
        }
        // Raw one-to-many segmentation head: [1,4+classes+32, N].
        final int channels=data.length;
        if(channels>=labels.size()+4 && data[0].length>20){
            int proposals=data[0].length;
            for(int j=0;j<proposals;j++){
                int cls=-1;float score=max;
                for(int k:wanted){
                    if(4+k>=channels)continue;
                    float s=data[4+k][j];
                    if(s>score){score=s;cls=k;}
                }
                if(cls<0)continue;
                float cx=data[0][j],cy=data[1][j],w=data[2][j],h=data[3][j];
                float x0=(cx-w/2)/SIZE,x1=(cx+w/2)/SIZE;
                float y0=(cy-h/2)/SIZE,y1=(cy+h/2)/SIZE;
                if(x1<=x0||y1<=y0)continue;
                max=score;
                best=new Match(new RectF(clamp(x0),clamp(y0),clamp(x1),clamp(y1)),labels.get(cls),score);
            }
        }
        return best;
    }
    public Match find(Bitmap src,String query) throws Exception {
        List<Integer> wanted=candidateLabels(query);
        if(wanted.isEmpty())return null;
        // Ultralytics models expect letterboxed 320x320 images, not distorted
        // portrait previews stretched into a square (previous implementation).
        // A central close-up is first: keys, straws and other small objects
        // become ~3x larger in the detector tensor on portrait phones.
        int w=src.getWidth(),h=src.getHeight();
        int side=Math.max(90,Math.round(Math.min(w,h)*.70f));
        Rect closeup=new Rect((w-side)/2,(h-side)/2,(w+side)/2,(h+side)/2);
        Match near=detect(src,query,wanted,closeup);
        if(near!=null)return near;
        // Always fall back to the complete frame if nothing matched centrally.
        return detect(src,query,wanted,new Rect(0,0,w,h));
    }
    /**
     * Searches the entire preview plus overlapping image tiles. A distant key may
     * occupy just a few detector pixels in the full-height portrait preview.
     * Each tile is resized separately to improve its apparent resolution.
     * Heavy scans run only during acquisition, never during optical-flow tracking.
     */
    public List<Match> findAcross(Bitmap source,String requested,int maxCandidates) throws Exception {
        List<Integer> wanted=candidateLabels(requested);
        if(wanted.isEmpty())return Collections.emptyList();
        int w=source.getWidth(),h=source.getHeight();
        ArrayList<Match> pool=new ArrayList<>();
        Match broad=detect(source,requested,wanted,new Rect(0,0,w,h));
        if(broad!=null)pool.add(broad);
        // Still scan tiles after an uncertain full-frame match.
        if(broad!=null && broad.score>=.49f) return pool;
        int tileW=Math.max(60,Math.round(w*.63f));
        int tileH=Math.max(60,Math.round(h*.44f));
        for(int row=0;row<3;row++){
            for(int col=0;col<2;col++){
                int x=Math.round((w-tileW)*(col/1f));
                int y=Math.round((h-tileH)*(row/2f));
                Match candidate=detect(source,requested,wanted,
                    new Rect(x,y,x+tileW,y+tileH));
                if(candidate==null)continue;
                boolean duplicate=false;
                for(Match earlier:pool){
                    RectF intersection=new RectF();
                    if(intersection.setIntersect(earlier.bounds,candidate.bounds)){
                        float union=earlier.bounds.width()*earlier.bounds.height()+
                            candidate.bounds.width()*candidate.bounds.height()-
                            intersection.width()*intersection.height();
                        if(union>0 && intersection.width()*intersection.height()/union>.32f){
                            duplicate=true;
                            if(candidate.score>earlier.score){pool.remove(earlier);duplicate=false;}
                            break;
                        }
                    }
                }
                if(!duplicate)pool.add(candidate);
                if(pool.size()>=maxCandidates && pool.stream().anyMatch(c->c.score>.44f))break;
            }
        }
        pool.sort((a,b)->Float.compare(b.score,a.score));
        if(pool.size()>maxCandidates)return new ArrayList<>(pool.subList(0,maxCandidates));
        return pool;
    }
    private Match detect(Bitmap src,String query,List<Integer> wanted,Rect crop) throws Exception {
        Bitmap input=Bitmap.createBitmap(SIZE,SIZE,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(input);
        canvas.drawColor(Color.rgb(114,114,114));
        final int width=crop.width(),height=crop.height();
        final float scale=Math.min(SIZE/(float)width,SIZE/(float)height);
        final float padx=(SIZE-width*scale)/2,pady=(SIZE-height*scale)/2;
        canvas.drawBitmap(src,crop,new RectF(padx,pady,padx+width*scale,pady+height*scale),
            new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] pixels=new int[SIZE*SIZE];
        input.getPixels(pixels,0,SIZE,0,0,SIZE,SIZE);
        input.recycle();
        FloatBuffer buf=ByteBuffer.allocateDirect(3*SIZE*SIZE*4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for(int c=0;c<3;c++){
            int shift=c==0?16:c==1?8:0;
            for(int pixel:pixels)buf.put(((pixel>>shift)&255)/255f);
        }
        buf.rewind();
        final long started=android.os.SystemClock.elapsedRealtime();
        try(OnnxTensor image=OnnxTensor.createTensor(env,buf,new long[]{1,3,SIZE,SIZE});
            OrtSession.Result results=session.run(Collections.singletonMap(inputName,image))){
            Object output=results.get(0).getValue();
            if(!(output instanceof float[][][]))throw new IllegalStateException("Unexpected visual detector tensor");
            Match candidate=parse((float[][][])output,wanted,query);
            if(candidate==null)return null;
            // Undo padding and map from this pass back to original full frame.
            float l=(candidate.bounds.left*SIZE-padx)/scale+crop.left;
            float t=(candidate.bounds.top*SIZE-pady)/scale+crop.top;
            float r=(candidate.bounds.right*SIZE-padx)/scale+crop.left;
            float b=(candidate.bounds.bottom*SIZE-pady)/scale+crop.top;
            RectF translated=new RectF(clamp(l/src.getWidth()),clamp(t/src.getHeight()),
                clamp(r/src.getWidth()),clamp(b/src.getHeight()));
            if(translated.width()<=.002f||translated.height()<=.002f)return null;
            android.util.Log.i("PlainSight","YOLOE "+query+" class="+candidate.label+" score="+
                candidate.score+" latency="+(android.os.SystemClock.elapsedRealtime()-started)+"ms");
            return new Match(translated,candidate.label,candidate.score);
        }
    }
    @Override public void close() throws Exception {session.close();}
}
