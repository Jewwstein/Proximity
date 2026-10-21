package com.plainsight.finder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder;
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder.ImageEmbedderOptions;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

public final class VisualMatcher implements AutoCloseable {
    private final ImageEmbedder model;
    private final Context context;
    private final Map<String, Memory> saved = new HashMap<>();
    public static final class Memory {
        public String name;
        public final List<float[]> angles = new ArrayList<>();
        public Memory(String value) { name = value; }
    }
    public static final class Match {
        public final RectF box;
        public final double score, advantage;
        Match(RectF b,double s,double a){box=b;score=s;advantage=a;}
    }
    public VisualMatcher(Context context) {
        this.context=context.getApplicationContext();
        BaseOptions opts=BaseOptions.builder().setModelAssetPath("mobilenet_v3_small.tflite").build();
        ImageEmbedderOptions config=ImageEmbedderOptions.builder().setBaseOptions(opts).build();
        model=ImageEmbedder.createFromOptions(this.context,config);
        read();
    }
    public synchronized Memory get(String query){return saved.get(clean(query));}
    public synchronized List<Memory> list(){return new ArrayList<>(saved.values());}
    public static String clean(String text) {
        return text.trim().toLowerCase(Locale.ROOT)
            .replaceFirst("^(please\\s+)?(can you\\s+)?(find|locate|spot|where is|where are|show me|look for)\\s+","")
            .replaceFirst("^(my|the|a|an)\\s+","")
            .replaceAll("[?!]+$","").trim();
    }
    private float[] embedding(Bitmap bitmap) {
        Bitmap square=Bitmap.createScaledBitmap(bitmap,224,224,true);
        float[] raw=model.embed(new BitmapImageBuilder(square).build())
                .embeddingResult().embeddings().get(0).floatEmbedding().clone();
        if(square!=bitmap)square.recycle();
        double norm=0;for(float f:raw)norm+=f*f;
        norm=Math.sqrt(norm)+1.e-8;
        for(int i=0;i<raw.length;i++)raw[i]/=(float)norm;
        return raw;
    }
    public float[] learnFrame(Bitmap frame) {
        int side=(int)(Math.min(frame.getWidth(),frame.getHeight())*.57);
        Bitmap crop=Bitmap.createBitmap(frame,(frame.getWidth()-side)/2,(frame.getHeight()-side)/2,side,side);
        try {return embedding(crop);}
        finally {crop.recycle();}
    }
    public synchronized void remember(String name,List<float[]> refs) {
        Memory item=new Memory(name);
        item.angles.addAll(refs);saved.put(clean(name),item);write();
    }
    public synchronized void forget(String name){saved.remove(clean(name));write();}
    private double cosine(float[] one,float[] two) {
        if(one.length!=two.length)return -1;
        double dot=0;for(int i=0;i<one.length;i++)dot+=one[i]*two[i];
        return dot;
    }
    public Match scan(Bitmap frame,Memory item) {
        if(item==null||item.angles.size()==0)return null;
        int w=frame.getWidth(),h=frame.getHeight();
        double top=-1,sum=0;int seen=0;
        RectF strongest=null;
        for(int pct:new int[]{30,50,78}) {
            int side=(int)(Math.min(w,h)*pct/100f);
            int cols=pct==30?4:3;
            int rows=pct==30?6:4;
            for(int y=0;y<rows;y++)for(int x=0;x<cols;x++){
                int left=Math.round((w-side)*x/(float)(cols-1));
                int upper=Math.round((h-side)*y/(float)(rows-1));
                Bitmap tile=Bitmap.createBitmap(frame,left,upper,side,side);
                float[] vec;
                try {vec=embedding(tile);} finally {tile.recycle();}
                double score=-1;
                for(float[] ref:item.angles)score=Math.max(score,cosine(vec,ref));
                seen++;sum+=score;
                if(score>top){
                    top=score;
                    strongest=new RectF(left/(float)w,upper/(float)h,(left+side)/(float)w,(upper+side)/(float)h);
                }
            }
        }
        if(strongest==null)return null;
        return new Match(strongest,top,top-sum/Math.max(1,seen));
    }
    private void write() {
        try{
            JSONObject all=new JSONObject();
            for(Memory item:saved.values()){
                JSONObject j=new JSONObject();j.put("name",item.name);
                JSONArray array=new JSONArray();
                for(float[] angle:item.angles){
                    JSONArray v=new JSONArray();for(float n:angle)v.put((double)n);array.put(v);
                }
                j.put("angles",array);all.put(clean(item.name),j);
            }
            context.getSharedPreferences("visual-memories-v3",Context.MODE_PRIVATE)
                    .edit().putString("objects",all.toString()).apply();
        }catch(Exception e){android.util.Log.e("PlainSight","Could not persist learned item",e);}
    }
    private void read(){
        try{
            String state=context.getSharedPreferences("visual-memories-v3",Context.MODE_PRIVATE).getString("objects","{}");
            JSONObject all=new JSONObject(state);
            Iterator<String> keys=all.keys();
            while(keys.hasNext()){
                String key=keys.next();JSONObject j=all.getJSONObject(key);
                Memory item=new Memory(j.optString("name",key));
                JSONArray vectors=j.getJSONArray("angles");
                for(int i=0;i<vectors.length();i++){
                    JSONArray a=vectors.getJSONArray(i);float[] v=new float[a.length()];
                    for(int k=0;k<a.length();k++)v[k]=(float)a.getDouble(k);
                    item.angles.add(v);
                }
                saved.put(key,item);
            }
        }catch(Exception e){android.util.Log.e("PlainSight","Saved memory could not load",e);}
    }
    @Override public void close(){model.close();}
}
