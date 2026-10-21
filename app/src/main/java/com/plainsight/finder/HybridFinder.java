package com.plainsight.finder;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/**
 * Staged acquisition: YOLOE generates locations; saved image features verify
 * personal identity; DINOv2 compares overall appearance when geometric
 * verification is inconclusive. Uncertain guesses are explicitly marked and
 * require user approval, never presented as verified finds.
 */
public final class HybridFinder {
    public static final class Match {
        public final RectF region;
        public final String label;
        public final String evidence;
        public final float score;
        public final boolean verified;
        public final ArrayList<RectF> alternatives;
        public Match(RectF box,String text,String provenance,float confidence){
            this(box,text,provenance,confidence,true,new ArrayList<>());
        }
        public Match(RectF box,String text,String provenance,float confidence,
                     boolean verified,ArrayList<RectF> suggestions){
            region=box==null?null:new RectF(box);
            label=text;evidence=provenance;score=confidence;
            this.verified=verified;alternatives=suggestions;
        }
    }
    private HybridFinder(){}
    private static JSONArray sampleReferences(String stored,int count)throws Exception{
        JSONArray all=new JSONArray(stored),chosen=new JSONArray();
        int n=Math.min(count,all.length());
        for(int i=0;i<n;i++)
            chosen.put(all.getString(Math.round((all.length()-1)*(i/(float)Math.max(1,n-1)))));
        return chosen;
    }
    private static boolean overlaps(RectF a,RectF b) {
        RectF intersect=new RectF();
        if(!intersect.setIntersect(a,b))return false;
        float area=intersect.width()*intersect.height();
        float union=a.width()*a.height()+b.width()*b.height()-area;
        return union>0 && area/union>.36f;
    }
    private static void appendUnique(ArrayList<RectF> list,RectF box){
        if(box==null||box.width()<.007||box.height()<.007)return;
        for(RectF prev:list)if(overlaps(prev,box))return;
        list.add(new RectF(box));
    }
    public static Match locate(Bitmap scene,String target,String stored,
           OfflineObjectFinder general,PersonalMatcher learned,
           DinoReidentifier dino) throws Exception {
        ArrayList<OfflineObjectFinder.Match> proposals=new ArrayList<>();
        if(general!=null&&general.supports(target)){
            proposals.addAll(general.findAcross(scene,target,stored==null?3:8,
                stored!=null&&!stored.isEmpty()));
        }
        if(stored==null||stored.isEmpty()){
            if(proposals.isEmpty())return null;
            OfflineObjectFinder.Match best=proposals.get(0);
            return new Match(best.bounds,best.label,"universal",best.score);
        }
        JSONArray views=sampleReferences(stored,12);
        // Geometric matching can prove identity from button and trim positions,
        // but glossy and distant items often have too few keypoints.
        if(learned!=null){
            for(int i=0;i<Math.min(2,proposals.size());i++){
                try{
                    ReferenceIdentityMatcher.Result check=
                        ReferenceIdentityMatcher.verify(scene,proposals.get(i).bounds,views);
                    if(check!=null)
                        return new Match(check.bounds,target,
                            "geometry verified: "+check.viewsAgree+
                            " reference angles, "+check.inliers+" consistent features",
                            proposals.get(i).score);
                }catch(Throwable error){Log.w("PlainSight","Geometric verification failed",error);}
            }
        }
        // Instance re-identification from overall learned appearance, not merely
        // class, color, or proximity to the center. The neural model is local.
        ArrayList<RectF> suggestions=new ArrayList<>();
        float primarySimilarity=-1;
        if(dino!=null&&!proposals.isEmpty()){
            try{
                List<DinoReidentifier.Candidate> sorted=dino.rank(scene,views,proposals);
                for(DinoReidentifier.Candidate p:sorted){
                    if(primarySimilarity<0)primarySimilarity=p.similarity;
                    appendUnique(suggestions,p.bounds);
                    if(suggestions.size()>=3)break;
                }
            }catch(Throwable error){Log.w("PlainSight","DINO re-identification unavailable",error);}
        }
        // ORB searches reference photos through the wider frame even when the
        // class detector misses the tiny physical item.
        if(learned!=null){
            try{
                PersonalMatcher.Match direct=learned.find(scene,views);
                if(direct!=null){
                    if(direct.inliers>=16){
                        return new Match(direct.bounds,target,
                            "multi-view geometric feature match ("+direct.inliers+" consistent features)",
                            Math.min(1f,direct.inliers/26f));
                    }
                    // Weak evidence belongs among POSSIBLE locations, never
                    // an automatically verified match.
                    if(direct.inliers>=7) {
                        ArrayList<RectF> reordered=new ArrayList<>();
                        appendUnique(reordered,direct.bounds);
                        for(RectF p:suggestions)appendUnique(reordered,p);
                        suggestions=reordered;
                    }
                }
            }catch(Throwable error){Log.w("PlainSight","ORB instance search failed",error);}
        }
        if(suggestions.isEmpty()){
            for(OfflineObjectFinder.Match p:proposals){
                appendUnique(suggestions,p.bounds);
                if(suggestions.size()>=3)break;
            }
        }
        // An unknown identity is never silently reported as FOUND. The user
        // can inspect/confirm candidates; a confirmation adds a real-world view.
        if(suggestions.isEmpty())return null;
        while(suggestions.size()>3)suggestions.remove(suggestions.size()-1);
        return new Match(suggestions.get(0),target,
            dino!=null&&primarySimilarity>=0
                ?"unverified neural appearance candidate"
                :"unverified general detection candidate",
            primarySimilarity>=0?primarySimilarity:0,
            false,suggestions);
    }
}
