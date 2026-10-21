package com.plainsight.finder;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Base64;
import android.util.Log;
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

/**
 * DINOv2-small appearance embeddings; specialized to distinguish actual
 * saved belongings from visually similar generic objects.
 * On-device only; used during acquisition, never in the tracking frame loop.
 */
public final class DinoReidentifier implements AutoCloseable {
    private static final int INPUT=224;
    private static final float[] MEAN={.485f,.456f,.406f};
    private static final float[] STD={.229f,.224f,.225f};
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private String cachedSignature="";
    private final ArrayList<float[]> cachedReferences=new ArrayList<>();
    public static final class Candidate {
        public final RectF bounds;
        public final String label;
        public final float detectorScore,similarity;
        public Candidate(RectF r,String n,float probability,float similarity){
            this.bounds=new RectF(r);label=n;detectorScore=probability;this.similarity=similarity;
        }
    }
    public DinoReidentifier(Context context) throws Exception {
        File destination=new File(context.getFilesDir(),"dinov2-small-v1.onnx");
        if(!destination.exists() || destination.length()<12000000){
            try(InputStream in=context.getAssets().open("dinov2-small.onnx");
                FileOutputStream out=new FileOutputStream(destination)){
                byte[] buffer=new byte[65536];int len;
                while((len=in.read(buffer))>0)out.write(buffer,0,len);
            }
        }
        environment=OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions settings=new OrtSession.SessionOptions();
        settings.setIntraOpNumThreads(2);
        settings.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session=environment.createSession(destination.getAbsolutePath(),settings);
        inputName=session.getInputNames().iterator().next();
    }
    private static Bitmap cropSquare(Bitmap source,RectF region,float expand){
        int w=source.getWidth(),h=source.getHeight();
        float cx=region.centerX()*w,cy=region.centerY()*h;
        float side=Math.max(24,Math.max(region.width()*w,region.height()*h)*expand);
        int l=Math.max(0,Math.round(cx-side*.5f)),t=Math.max(0,Math.round(cy-side*.5f));
        int r=Math.min(w,Math.round(cx+side*.5f)),b=Math.min(h,Math.round(cy+side*.5f));
        if(r-l<5||b-t<5)return null;
        Bitmap tile=Bitmap.createBitmap(source,l,t,r-l,b-t);
        if(tile.getWidth()==tile.getHeight())return tile;
        Bitmap square=Bitmap.createBitmap(Math.max(tile.getWidth(),tile.getHeight()),
            Math.max(tile.getWidth(),tile.getHeight()),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(square);
        canvas.drawColor(Color.rgb(125,125,125));
        float offsetX=(square.getWidth()-tile.getWidth())*.5f;
        float offsetY=(square.getHeight()-tile.getHeight())*.5f;
        canvas.drawBitmap(tile,offsetX,offsetY,new Paint(Paint.FILTER_BITMAP_FLAG));
        tile.recycle();
        return square;
    }
    private float[] embed(Bitmap image) throws Exception {
        Bitmap resized=Bitmap.createScaledBitmap(image,INPUT,INPUT,true);
        int[] argb=new int[INPUT*INPUT];
        resized.getPixels(argb,0,INPUT,0,0,INPUT,INPUT);
        if(resized!=image)resized.recycle();
        FloatBuffer floats=ByteBuffer.allocateDirect(INPUT*INPUT*3*4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        for(int channel=0;channel<3;channel++){
            int shift=channel==0?16:channel==1?8:0;
            for(int pixel:argb){
                float component=((pixel>>shift)&255)/255f;
                floats.put((component-MEAN[channel])/STD[channel]);
            }
        }
        floats.rewind();
        try(OnnxTensor t=OnnxTensor.createTensor(environment,floats,new long[]{1,3,INPUT,INPUT});
            OrtSession.Result result=session.run(Collections.singletonMap(inputName,t))){
            Object raw=result.get(0).getValue();
            if(!(raw instanceof float[][][]))
                throw new IllegalStateException("Unexpected DINOv2 output shape");
            float[][] tokens=((float[][][])raw)[0];
            if(tokens.length==0||tokens[0].length<100)
                throw new IllegalStateException("DINOv2 output empty");
            float[] cls=tokens[0].clone();
            double norm=0;
            for(float value:cls)norm+=value*value;
            norm=Math.sqrt(norm)+1.e-9;
            for(int i=0;i<cls.length;i++)cls[i]/=(float)norm;
            return cls;
        }
    }
    private static float similarity(float[] one,float[] two){
        if(one.length!=two.length)return -1;
        float v=0;for(int i=0;i<one.length;i++)v+=one[i]*two[i];
        return v;
    }
    private void prepare(JSONArray saved) throws Exception {
        String signature=saved.length()+":"+
            (saved.length()>0?saved.optString(0,"").hashCode():0)+":"+
            (saved.length()>0?saved.optString(saved.length()-1,"").hashCode():0);
        if(signature.equals(cachedSignature)&&!cachedReferences.isEmpty())return;
        cachedReferences.clear();cachedSignature="";
        int count=Math.min(saved.length(),9);
        for(int i=0;i<count;i++){
            int index=Math.round(i*(saved.length()-1f)/Math.max(1,count-1));
            byte[] jpg=Base64.decode(saved.getString(index),Base64.DEFAULT);
            Bitmap reference=BitmapFactory.decodeByteArray(jpg,0,jpg.length);
            if(reference==null)continue;
            try {
                // The saved object was deliberately centered inside the
                // training guide; remove some of the hand/background.
                Bitmap centered=cropSquare(reference,
                    new RectF(.13f,.13f,.87f,.87f),1);
                if(centered==null)continue;
                try{cachedReferences.add(embed(centered));}
                finally{centered.recycle();}
            } finally {reference.recycle();}
        }
        cachedSignature=signature;
        Log.i("PlainSight","DINOv2 cached "+cachedReferences.size()+" learned angles.");
    }
    public synchronized List<Candidate> rank(Bitmap scene,JSONArray saved,
                                              List<OfflineObjectFinder.Match> locations) throws Exception {
        if(locations.isEmpty())return Collections.emptyList();
        prepare(saved);
        if(cachedReferences.isEmpty())return Collections.emptyList();
        ArrayList<Candidate> ranked=new ArrayList<>();
        int n=Math.min(8,locations.size());
        for(int i=0;i<n;i++){
            OfflineObjectFinder.Match location=locations.get(i);
            Bitmap tile=cropSquare(scene,location.bounds,1.13f);
            if(tile==null)continue;
            float[] embedding;
            try{embedding=embed(tile);}
            finally{tile.recycle();}
            float best=-1;
            for(float[] memory:cachedReferences)
                best=Math.max(best,similarity(embedding,memory));
            ranked.add(new Candidate(location.bounds,location.label,location.score,best));
        }
        ranked.sort((a,b)->Float.compare(b.similarity,a.similarity));
        Log.i("PlainSight","DINO ranked "+ranked.size()+" candidates; top cosine "+
             (ranked.isEmpty()?"N/A":ranked.get(0).similarity));
        return ranked;
    }
    @Override public synchronized void close() throws Exception {
        cachedReferences.clear();session.close();
    }
}
