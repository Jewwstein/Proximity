package com.plainsight.finder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.util.Base64;

import org.json.JSONArray;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

// Conservative appearance fallback for saved objects that lack ORB keypoints.
// This is a photograph-template comparison, not a universal semantic detector.
public final class TemplateMatcher {
    private TemplateMatcher(){}
    private static Mat gray(Bitmap b){
        Mat src=new Mat(),out=new Mat();
        Utils.bitmapToMat(b,src);
        Imgproc.cvtColor(src,out,Imgproc.COLOR_RGBA2GRAY);
        src.release();
        return out;
    }
    private static float clamp(float v){return Math.max(0,Math.min(1,v));}
    public static RectF find(Bitmap frame,JSONArray references){
        if(frame==null||references==null||references.length()==0)return null;
        Mat sceneRaw=gray(frame),scene=new Mat();
        float factor=Math.min(1f,680f/Math.max(sceneRaw.cols(),sceneRaw.rows()));
        Imgproc.resize(sceneRaw,scene,new Size(Math.max(1,(int)(sceneRaw.cols()*factor)),
            Math.max(1,(int)(sceneRaw.rows()*factor))));
        sceneRaw.release();
        float strongest=.77f;
        RectF result=null;
        try {
            int stride=references.length()>4?2:1;
            for(int index=0;index<references.length();index+=stride){
                byte[] encoded=Base64.decode(references.optString(index,""),Base64.DEFAULT);
                Bitmap reference=BitmapFactory.decodeByteArray(encoded,0,encoded.length);
                if(reference==null)continue;
                Mat photo=gray(reference);
                reference.recycle();
                try {
                    // The user is instructed to hold the object at the center of the guide.
                    int borderX=(int)(photo.cols()*.21f);
                    int borderY=(int)(photo.rows()*.21f);
                    Mat patch=new Mat(photo,new Rect(borderX,borderY,
                        photo.cols()-2*borderX,photo.rows()-2*borderY));
                    try {
                        Mat mean=new Mat(),std=new Mat();
                        Core.meanStdDev(patch,mean,std);
                        double variation=std.get(0,0)[0];
                        mean.release();std.release();
                        if(variation<13)continue; // flat-colored image is not discriminative
                        for(float scale:new float[]{.34f,.48f,.66f,.86f}){
                            int width=Math.round(patch.cols()*scale),height=Math.round(patch.rows()*scale);
                            if(width<18||height<18||width>=scene.cols()||height>=scene.rows())continue;
                            Mat adjusted=new Mat(),scoreMap=new Mat();
                            try {
                                Imgproc.resize(patch,adjusted,new Size(width,height),0,0,Imgproc.INTER_AREA);
                                Imgproc.matchTemplate(scene,adjusted,scoreMap,Imgproc.TM_CCOEFF_NORMED);
                                Core.MinMaxLocResult score=Core.minMaxLoc(scoreMap);
                                if(score.maxVal>strongest){
                                    strongest=(float)score.maxVal;
                                    float l=(float)(score.maxLoc.x/scene.cols());
                                    float t=(float)(score.maxLoc.y/scene.rows());
                                    float r=(float)((score.maxLoc.x+width)/scene.cols());
                                    float b=(float)((score.maxLoc.y+height)/scene.rows());
                                    result=new RectF(clamp(l),clamp(t),clamp(r),clamp(b));
                                }
                            }finally {adjusted.release();scoreMap.release();}
                        }
                    }finally{patch.release();}
                }finally{photo.release();}
            }
            android.util.Log.i("PlainSight","Saved-image template peak "+strongest+" found="+(result!=null));
            return result;
        }catch(Exception e){
            android.util.Log.w("PlainSight","Saved template matching failed",e);
            return null;
        }finally {scene.release();}
    }
}
