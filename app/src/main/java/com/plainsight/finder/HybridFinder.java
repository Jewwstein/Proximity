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
            candidates=general.findAcross(scene,target,references==null?3:6,
                references!=null && !references.isEmpty());
        }
        if(references==null||references.isEmpty()){
            if(candidates.isEmpty())return null;
            OfflineObjectFinder.Match one=candidates.get(0);
            return new Match(one.bounds,one.label,"universal",one.score);
        }
        if(learned==null){
            // An unavailable identity matcher must never downgrade a saved
            // person's item to generic color/shape guesses.
            Log.w("PlainSight","Saved-item matcher unavailable; refusing generic false positives.");
            return null;
        }
        // A saved identity is not the same thing as a generic category match.
        // NEVER advertise "found my keys" solely because YOLOE saw an object
        // that is roughly key-shaped or dark. Mouse/controller confusion came
        // from accepting that low-confidence fallback in the old code.
        JSONArray refs=sampleReferences(references,12);
        int checked=0;
        for(OfflineObjectFinder.Match proposal:candidates){
            checked++;
            try {
                ReferenceIdentityMatcher.Result identity=
                    ReferenceIdentityMatcher.verify(scene,proposal.bounds,refs);
                if(identity!=null){
                    return new Match(identity.bounds,target,
                        "EXACT reference detail: "+identity.viewsAgree+
                        " angles · "+identity.inliers+" spatial features",proposal.score);
                }
            }catch(Throwable e){
                Log.w("PlainSight","Identity verification rejected an uncertain candidate",e);
            }
            if(checked>=6)break;
        }
        // Independent geometric search can localize a saved personal item
        // when the category detector missed something small or off-center.
        // Seven accidental keypoints are not identity verification. Require
        // stronger evidence and validate the returned area against references.
        try {
            PersonalMatcher.Match referenceHit=learned.find(scene,refs);
            if(referenceHit!=null && referenceHit.inliers>=12){
                ReferenceIdentityMatcher.Result verified=
                    ReferenceIdentityMatcher.verify(scene,referenceHit.bounds,refs);
                if(verified!=null) {
                    return new Match(verified.bounds,target,
                        "reference-only match · "+verified.viewsAgree+
                        " angles · "+verified.inliers+" distinctive features",
                        Math.min(1f,verified.inliers/24f));
                }

            }
        }catch(Throwable e){
            Log.w("PlainSight","Reference-only localization unavailable",e);
        }
        // A false negative is preferable to highlighting a mouse as someone's
        // saved key fob. Leave the view clear when identity is unverified.
        if(!candidates.isEmpty()){
            Log.i("PlainSight","Rejected "+candidates.size()+
                " generic candidates because none matched the saved item.");
        }
        return null;
    }
}
