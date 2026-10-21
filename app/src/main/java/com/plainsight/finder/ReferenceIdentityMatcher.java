package com.plainsight.finder;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Base64;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.opencv.android.Utils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.SIFT;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that an instance detected by the generic detector matches the
 * saved physical item. Uses SIFT keypoint descriptors, mutual spatial
 * geometry and multi-view confirmation—not color or semantic category.
 *
 * It is intentionally conservative: an uncertain match is NOT a find.
 */
public final class ReferenceIdentityMatcher {
    public static final class Result {
        public final RectF bounds;
        public final int inliers;
        public final int viewsAgree;
        Result(RectF bounds,int inliers,int agreeing){
            this.bounds=bounds;this.inliers=inliers;this.viewsAgree=agreeing;
        }
    }
    private static final class Sample {
        final Mat desc;
        final MatOfKeyPoint kp;
        final int width,height;
        Sample(Mat d,MatOfKeyPoint k,int w,int h){desc=d;kp=k;width=w;height=h;}
        void close(){desc.release();kp.release();}
    }
    private static final int MAX_CACHED_VIEWS=15;
    private static String cacheText=null;
    private static final ArrayList<Sample> cache=new ArrayList<>();

    private static void dropCache(){
        for(Sample item:cache)item.close();
        cache.clear();
    }
    private static Mat gray(Bitmap source){
        Mat rgba=new Mat(),result=new Mat();
        Utils.bitmapToMat(source,rgba);
        Imgproc.cvtColor(rgba,result,Imgproc.COLOR_RGBA2GRAY);
        rgba.release();
        return result;
    }
    private static Sample descriptors(Bitmap frame,SIFT sift,boolean reference){
        Mat original=gray(frame);
        Mat resized=new Mat(),desc=new Mat();
        MatOfKeyPoint kp=new MatOfKeyPoint();
        try{
            int max=reference?370:520;
            double factor=Math.min(1d,max/(double)Math.max(original.rows(),original.cols()));
            Imgproc.resize(original,resized,new Size(Math.max(24,Math.round(original.cols()*factor)),
                Math.max(24,Math.round(original.rows()*factor))),0,0,Imgproc.INTER_AREA);
            // The learning guide held the item near the middle.
            // Discard the outer hand/background border of learned references.
            Mat mask=new Mat();
            if(reference){
                mask=Mat.zeros(resized.rows(),resized.cols(),org.opencv.core.CvType.CV_8UC1);
                int l=Math.round(resized.cols()*.13f),t=Math.round(resized.rows()*.13f);
                Imgproc.rectangle(mask,new Point(l,t),
                    new Point(resized.cols()-l,resized.rows()-t),
                    new org.opencv.core.Scalar(255),-1);
            }
            try{sift.detectAndCompute(resized,mask,kp,desc);}
            finally{mask.release();}
            if(desc.empty()||kp.rows()<7){desc.release();kp.release();return null;}
            return new Sample(desc,kp,resized.cols(),resized.rows());
        }finally{original.release();resized.release();}
    }
    private static synchronized ArrayList<Sample> getCached(JSONArray data,SIFT sift) throws Exception {
        String fingerprint=data.toString();
        if(fingerprint.equals(cacheText))return new ArrayList<>(cache);
        dropCache();
        cacheText=null;
        int n=data.length();
        for(int i=0;i<Math.min(n,MAX_CACHED_VIEWS);i++){
            int chosen=Math.round(i*(n-1f)/Math.max(1,Math.min(n,MAX_CACHED_VIEWS)-1));
            byte[] bytes=Base64.decode(data.getString(chosen),Base64.DEFAULT);
            Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
            if(image==null)continue;
            try{
                Sample sample=descriptors(image,sift,true);
                if(sample!=null)cache.add(sample);
            }finally{image.recycle();}
        }
        cacheText=fingerprint;
        return new ArrayList<>(cache);
    }
    private static RectF projectedRect(Sample ref,Sample target,Mat H){
        MatOfPoint2f corners=new MatOfPoint2f(
            new Point(ref.width*.14,ref.height*.14),
            new Point(ref.width*.86,ref.height*.14),
            new Point(ref.width*.86,ref.height*.86),
            new Point(ref.width*.14,ref.height*.86));
        MatOfPoint2f mapped=new MatOfPoint2f();
        try{
            Core.perspectiveTransform(corners,mapped,H);
            Point[] p=mapped.toArray();
            if(p.length!=4)return null;
            float l=Float.MAX_VALUE,t=Float.MAX_VALUE,r=-Float.MAX_VALUE,b=-Float.MAX_VALUE;
            for(Point point:p){
                if(!Double.isFinite(point.x)||!Double.isFinite(point.y))return null;
                l=Math.min(l,(float)point.x);t=Math.min(t,(float)point.y);
                r=Math.max(r,(float)point.x);b=Math.max(b,(float)point.y);
            }
            RectF roi=new RectF(l/target.width,t/target.height,r/target.width,b/target.height);
            // Reject wild/projective coincidences with unrelated items.
            if(roi.width()<.045f||roi.height()<.045f||
               roi.width()>1.4f||roi.height()>1.4f||
               roi.centerX()<-.2f||roi.centerY()<-.2f||
               roi.centerX()>1.2f||roi.centerY()>1.2f)return null;
            return roi;
        }finally{corners.release();mapped.release();}
    }
    private static final class Evidence {
        final RectF rect;
        final int inliers;
        Evidence(RectF rect,int inliers){this.rect=rect;this.inliers=inliers;}
    }
    private static Evidence evaluate(Sample ref,Sample target,BFMatcher bf){
        if(ref.desc.empty()||target.desc.empty())return null;
        List<MatOfDMatch> candidates=new ArrayList<>();
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();
        MatOfByte mask=new MatOfByte();
        Mat H=null;
        try{
            bf.knnMatch(ref.desc,target.desc,candidates,2);
            KeyPoint[] rk=ref.kp.toArray(),tk=target.kp.toArray();
            ArrayList<Point> from=new ArrayList<>(),to=new ArrayList<>();
            for(MatOfDMatch pair:candidates){
                DMatch[] a=pair.toArray();
                if(a.length>=2&&a[0].distance<a[1].distance*.69f &&
                    a[0].queryIdx<rk.length&&a[0].trainIdx<tk.length){
                    from.add(rk[a[0].queryIdx].pt);
                    to.add(tk[a[0].trainIdx].pt);
                }
            }
            if(from.size()<8)return null;
            src.fromList(from);dst.fromList(to);
            H=Calib3d.findHomography(src,dst,Calib3d.RANSAC,3,mask);
            if(H==null||H.empty())return null;
            int verified=Core.countNonZero(mask);
            if(verified<8||verified<from.size()*.53f)return null;
            RectF box=projectedRect(ref,target,H);
            return box==null?null:new Evidence(box,verified);
        }finally{
            for(MatOfDMatch pair:candidates)pair.release();
            src.release();dst.release();mask.release();
            if(H!=null)H.release();
        }
    }
    public static synchronized Result verify(Bitmap scene,RectF proposal,JSONArray refs) throws Exception {
        if(scene==null||proposal==null||refs==null||refs.length()==0)return null;
        SIFT sift=SIFT.create(500);
        List<Sample> training=getCached(refs,sift);
        if(training.isEmpty())return null;
        int w=scene.getWidth(),h=scene.getHeight();
        float cx=proposal.centerX()*w,cy=proposal.centerY()*h;
        float rw=Math.max(55,proposal.width()*w*1.5f);
        float rh=Math.max(55,proposal.height()*h*1.5f);
        int l=Math.max(0,Math.round(cx-rw*.5f)),t=Math.max(0,Math.round(cy-rh*.5f));
        int r=Math.min(w,Math.round(cx+rw*.5f)),b=Math.min(h,Math.round(cy+rh*.5f));
        if(r-l<30||b-t<30)return null;
        Bitmap roi=Bitmap.createBitmap(scene,l,t,r-l,b-t);
        Sample observed=null;
        BFMatcher bf=BFMatcher.create(Core.NORM_L2,false);
        try{
            observed=descriptors(roi,sift,false);
            if(observed==null)return null;
            ArrayList<Evidence> positive=new ArrayList<>();
            for(Sample ref:training){
                Evidence value=evaluate(ref,observed,bf);
                if(value==null)continue;
                float x=(l+value.rect.centerX()*(r-l))/(float)w;
                float y=(t+value.rect.centerY()*(b-t))/(float)h;
                // Geometry must land on the actual proposed object rather than
                // matching the desk or background beside it.
                if(Math.abs(x-proposal.centerX())>
                   Math.max(.05f,proposal.width()*.85f) ||
                   Math.abs(y-proposal.centerY())>
                   Math.max(.05f,proposal.height()*.85f))continue;
                positive.add(value);
            }
            int best=0;Evidence winner=null;
            for(Evidence e:positive){
                if(e.inliers>best){winner=e;best=e.inliers;}
            }
            // An independent second view is preferred. One view needs many more
            // distinctive button/trim/texture correspondences to be accepted.
            if(winner==null||(positive.size()<2&&best<14))return null;
            RectF q=winner.rect;
            RectF mapped=new RectF(
                Math.max(0,(l+q.left*(r-l))/(float)w),
                Math.max(0,(t+q.top*(b-t))/(float)h),
                Math.min(1,(l+q.right*(r-l))/(float)w),
                Math.min(1,(t+q.bottom*(b-t))/(float)h));
            if(mapped.width()<.003f||mapped.height()<.003f)return null;
            Log.i("PlainSight","SIFT exact identity verified views="+positive.size()+" inliers="+best);
            return new Result(mapped,best,positive.size());
        }finally{
            if(observed!=null)observed.close();
            roi.recycle();
        }
    }
}
