package com.plainsight.finder;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Base64;
import android.graphics.BitmapFactory;
import android.util.Log;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/**
 * Fusion of general object detection and learned multi-view image matching.
 * References help distinguish a personal item; they never disable the general
 * detector. All returned regions are normalized to the ORIGINAL live frame.
 */
public final class HybridFinder {
    public static final class Match {
        public final RectF region;
        public final String label;
        public final String evidence;
        public final float score;
        public Match(RectF box,String text,String provenance,float confidence){
            region=box;label=text;evidence=provenance;score=confidence;
        }
    }
    private HybridFinder(){}
    private static float clamp(float v){return Math.max(0,Math.min(1,v));}
    private static Rect cropAround(RectF box,int w,int h){
        float cx=box.centerX()*w,cy=box.centerY()*h;
        float cw=Math.max(w*.24f,box.width()*w*2.9f);
        float ch=Math.max(h*.22f,box.height()*h*2.9f);
        int left=Math.max(0,Math.round(cx-cw/2)), top=Math.max(0,Math.round(cy-ch/2));
        int right=Math.min(w,Math.round(cx+cw/2)), bottom=Math.min(h,Math.round(cy+ch/2));
        if(right-left<40||bottom-top<40)return new Rect(0,0,w,h);
        return new Rect(left,top,right,bottom);
    }
    private static JSONArray sampleReferences(String stored,int count) throws Exception {
        JSONArray all=new JSONArray(stored);
        JSONArray subset=new JSONArray();
        if(all.length()<=count)return all;
        for(int i=0;i<count;i++){
            int at=Math.round((all.length()-1)*(i/(float)Math.max(1,count-1)));
            subset.put(all.getString(at));
        }
        return subset;
    }
    private static RectF normalize(RectF local,Rect crop,int width,int height){
        return new RectF(
            clamp((crop.left+local.left*crop.width())/width),
            clamp((crop.top+local.top*crop.height())/height),
            clamp((crop.left+local.right*crop.width())/width),
            clamp((crop.top+local.bottom*crop.height())/height));
    }
    public static Match locate(Bitmap scene,String target,String references,
         OfflineObjectFinder general,PersonalMatcher learned) throws Exception {
        List<OfflineObjectFinder.Match> candidates=new ArrayList<>();
        if(general!=null&&general.supports(target)){
            candidates=general.findAcross(scene,target,references==null?3:5);
        }
        if(references==null||references.isEmpty()||learned==null){
            if(candidates.isEmpty())return null;
            OfflineObjectFinder.Match one=candidates.get(0);
            return new Match(one.bounds,one.label,"universal",one.score);
        }
        JSONArray refs=sampleReferences(references,10);
        // Verify general proposals with multi-angle appearance. Regions near the
        // edge of the frame are included in the general scan.
        int w=scene.getWidth(),h=scene.getHeight();
        int checks=Math.min(3,candidates.size());
        for(int i=0;i<checks;i++){
            OfflineObjectFinder.Match proposal=candidates.get(i);
            Rect roi=cropAround(proposal.bounds,w,h);
            Bitmap patch=Bitmap.createBitmap(scene,roi.left,roi.top,roi.width(),roi.height());
            try{
                PersonalMatcher.Match confirmed=learned.find(patch,refs);
                if(confirmed!=null){
                    RectF mapped=normalize(confirmed.bounds,roi,w,h);
                    if(mapped.width()>.002f&&mapped.height()>.002f)
                        return new Match(mapped,target,"universal + saved 360 reference ("+
                             confirmed.inliers+" geometric matches)",proposal.score);
                }
            }catch(Exception error){
                Log.w("PlainSight","Reference candidate check failed",error);
            }finally{patch.recycle();}
        }
        // Search for the particular object from learned reference angles even
        // if the general category detector offered no boxes.
        try{
            PersonalMatcher.Match personal=learned.find(scene,refs);
            if(personal!=null){
                return new Match(personal.bounds,target,"saved multi-view geometry",
                    Math.min(1f,personal.inliers/28f));
            }
        }catch(Exception error){
            Log.w("PlainSight","Full-scene reference matching failed",error);
        }
        // Never block the general detector merely because a learned reference
        // wasn't convincing: explicitly describe it as an unverified candidate.
        if(!candidates.isEmpty()){
            OfflineObjectFinder.Match best=candidates.get(0);
            return new Match(best.bounds,best.label,"universal candidate (saved identity unverified)",best.score);
        }
        return null;
    }
}
