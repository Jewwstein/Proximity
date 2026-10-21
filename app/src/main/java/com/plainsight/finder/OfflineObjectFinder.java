package com.plainsight.finder;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
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
    private Match parse(float[][][] arr,List<Integer> wanted) {
        if(arr==null||arr.length==0)return null;
        float[][] data=arr[0];if(data.length==0)return null;
        float max=.25f;Match best=null;
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
        Bitmap img=Bitmap.createScaledBitmap(src,SIZE,SIZE,true);
        int[] argb=new int[SIZE*SIZE];img.getPixels(argb,0,SIZE,0,0,SIZE,SIZE);
        if(img!=src)img.recycle();
        // Float tensor in NCHW channel order. This is isolated off the main/UI thread.
        FloatBuffer b=ByteBuffer.allocateDirect(3*SIZE*SIZE*4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for(int channel=0;channel<3;channel++){
            int shift=channel==0?16:channel==1?8:0;
            for(int pixel:argb)b.put(((pixel>>shift)&255)/255f);
        }
        b.rewind();
        long start=android.os.SystemClock.elapsedRealtime();
        try(OnnxTensor input=OnnxTensor.createTensor(env,b,new long[]{1,3,SIZE,SIZE});
            OrtSession.Result result=session.run(Collections.singletonMap(inputName,input))){
            Object out=result.get(0).getValue();
            if(!(out instanceof float[][][]))throw new IllegalStateException("Unexpected detector output tensor");
            Match best=parse((float[][][])out,wanted);
            android.util.Log.i("PlainSight","YOLOE inference "+(android.os.SystemClock.elapsedRealtime()-start)+"ms target="+query+" found="+(best!=null));
            return best;
        }
    }
    @Override public void close() throws Exception {session.close();}
}
