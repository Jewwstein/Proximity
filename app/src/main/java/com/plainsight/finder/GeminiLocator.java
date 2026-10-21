package com.plainsight.finder;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class GeminiLocator {
    private static final String URL_STRING="https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent";
    public static final class Result {
        public final boolean found;
        public final float left,top,right,bottom;
        public final String label,description;
        Result(boolean found,float left,float top,float right,float bottom,String label,String description) {
            this.found=found;this.left=left;this.top=top;this.right=right;this.bottom=bottom;
            this.label=label;this.description=description;
        }
    }
    private GeminiLocator(){}
    private static String base64(Bitmap bmp) throws Exception {
        if(bmp==null)throw new IllegalArgumentException("Camera image not ready");
        int longer=Math.max(bmp.getWidth(),bmp.getHeight());
        float scale=Math.min(1f,1152f/longer);
        Bitmap input=scale<1f?Bitmap.createScaledBitmap(bmp,Math.max(1,Math.round(bmp.getWidth()*scale)),
            Math.max(1,Math.round(bmp.getHeight()*scale)),true):bmp;
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(!input.compress(Bitmap.CompressFormat.JPEG,83,bytes))throw new Exception("Could not compress image");
            return Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP);
        }finally{if(input!=bmp)input.recycle();}
    }
    private static JSONObject imagePart(String encoded) throws Exception{
        JSONObject inline=new JSONObject();
        inline.put("mime_type","image/jpeg");
        inline.put("data",encoded);
        return new JSONObject().put("inline_data",inline);
    }
    private static String readAll(InputStream stream) throws Exception {
        if(stream==null)return "";
        try(InputStream input=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int count;
            while((count=input.read(buf))!=-1) {
                out.write(buf,0,count);
                if(out.size()>1000000)break;
            }
            return out.toString("UTF-8");
        }
    }
    public static Result locate(String key,String requested,Bitmap scene,List<Bitmap> refs) throws Exception {
        if(key==null||key.trim().isEmpty())throw new IllegalArgumentException("AI key missing");
        if(requested==null||requested.trim().isEmpty())throw new IllegalArgumentException("Object name missing");
        JSONArray parts=new JSONArray();
        String prompt;
        if(refs!=null&&!refs.isEmpty()){
            prompt="You are a precise image-grounding system. The first "+refs.size()+
                " images are REFERENCE photographs of one particular personal object called '"+requested+
                "'. The FINAL image is a LIVE CAMERA SCENE. Locate that same physical object in the FINAL IMAGE ONLY, even if rotated or surrounded by clutter. "+
                "Use the references for appearance, not coordinates. Do not mark a different similar object. "+
                "If the target is too small, not visible, occluded, or uncertain, set found=false and box_2d=[0,0,0,0]. "+
                "If visibly present, return one TIGHT box around the item. box_2d coordinates are integer [ymin,xmin,ymax,xmax] normalized 0 to 1000 "+
                "RELATIVE TO THE FINAL scene image, never the references. Set label and short description. Reply ONLY valid JSON.";
        }else {
            prompt="You are an accurate visual object locator. The following camera image is the scene. "+
                "Find the object matching this request: '"+requested+"'. "+
                "This may be a specific everyday object, color, brand, shape, clothing, keys, food or tool. "+
                "If it is NOT visibly identifiable, too small or hidden, return found=false and box_2d=[0,0,0,0]. "+
                "DO NOT invent or guess. If found, return a single TIGHT 2D bounding rectangle around the target. "+
                "Coordinates must be integer [ymin,xmin,ymax,xmax] normalized from 0 to 1000 relative to the scene image. "+
                "Set label and short description. Reply ONLY valid JSON.";
        }
        parts.put(new JSONObject().put("text",prompt));
        if(refs!=null){
            for(int i=0;i<refs.size();i++){
                parts.put(new JSONObject().put("text","Reference image "+(i+1)+" of "+refs.size()+":"));
                parts.put(imagePart(base64(refs.get(i))));
            }
            parts.put(new JSONObject().put("text","FINAL live camera scene:"));
        }
        parts.put(imagePart(base64(scene)));
        JSONObject schema=new JSONObject();
        schema.put("type","OBJECT");
        JSONObject fields=new JSONObject();
        fields.put("found",new JSONObject().put("type","BOOLEAN"));
        fields.put("label",new JSONObject().put("type","STRING"));
        fields.put("description",new JSONObject().put("type","STRING"));
        fields.put("box_2d",new JSONObject().put("type","ARRAY")
            .put("items",new JSONObject().put("type","INTEGER")));
        schema.put("properties",fields);
        schema.put("required",new JSONArray().put("found").put("label").put("description").put("box_2d"));
        JSONObject config=new JSONObject().put("responseMimeType","application/json")
            .put("responseSchema",schema)
            .put("thinkingConfig",new JSONObject().put("thinkingLevel","low"));
        JSONObject request=new JSONObject()
            .put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",parts)))
            .put("generationConfig",config);
        HttpURLConnection conn=null;
        try{
            conn=(HttpURLConnection)new URL(URL_STRING).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(45000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type","application/json");
            conn.setRequestProperty("x-goog-api-key",key.trim());
            byte[] data=request.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);
            try(OutputStream stream=conn.getOutputStream()){stream.write(data);}
            int status=conn.getResponseCode();
            String body=readAll(status>=200&&status<300?conn.getInputStream():conn.getErrorStream());
            if(status<200||status>=300) {
                String error=body;
                try{error=new JSONObject(body).getJSONObject("error").optString("message",body);}catch(Exception ignored){}
                if(error.length()>250)error=error.substring(0,250);
                throw new Exception("Gemini HTTP "+status+": "+error);
            }
            JSONObject json=new JSONObject(body);
            JSONArray candidates=json.optJSONArray("candidates");
            if(candidates==null||candidates.length()==0)
                throw new Exception("Gemini returned no candidates (image may have been blocked).");
            JSONArray responseParts=candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            StringBuilder txt=new StringBuilder();
            for(int i=0;i<responseParts.length();i++){
                String t=responseParts.getJSONObject(i).optString("text","");
                if(!t.isEmpty())txt.append(t);
            }
            String raw=txt.toString().trim().replaceFirst("^\\x60\\x60\\x60(?:json)?\\s*","").replaceFirst("\\x60\\x60\\x60$","").trim();
            if(raw.startsWith("[")){
                JSONArray arr=new JSONArray(raw);
                if(arr.length()==0)return new Result(false,0,0,0,0,"","Nothing visibly matched");
                raw=arr.getJSONObject(0).toString();
            }
            JSONObject answer=new JSONObject(raw);
            String label=answer.optString("label",requested);
            String desc=answer.optString("description","");
            boolean found=answer.optBoolean("found",false);
            JSONArray b=answer.optJSONArray("box_2d");
            if(!found||b==null||b.length()!=4)
                return new Result(false,0,0,0,0,label,desc);
            int y0=b.optInt(0,-1),x0=b.optInt(1,-1),y1=b.optInt(2,-1),x1=b.optInt(3,-1);
            if(x0<0||y0<0||x1>1000||y1>1000||x1<=x0||y1<=y0)
                return new Result(false,0,0,0,0,label,"AI returned an invalid box.");
            return new Result(true,x0/1000f,y0/1000f,x1/1000f,y1/1000f,label,desc);
        }finally{
            if(conn!=null)conn.disconnect();
        }
    }
}
