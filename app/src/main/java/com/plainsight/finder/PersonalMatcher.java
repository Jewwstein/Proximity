package com.plainsight.finder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;
import java.util.ArrayList;
import java.util.List;

// Independently learns actual image features from saved user images.
// Does not rely on YOLO category names to find the user's key fob.
public final class PersonalMatcher {
    public static final class Match {
        public final RectF bounds;
        public final int inliers;
        public final int references;
        public Match(RectF bounds,int inliers,int references){
            this.bounds=bounds;this.inliers=inliers;this.references=references;
        }
    }
    public PersonalMatcher(Context ignored) {
        if(!OpenCVLoader.initLocal())throw new IllegalStateException("OpenCV mobile image matcher unavailable");
    }
    private Mat gray(Bitmap b) {
        Mat rgba=new Mat(),out=new Mat();
        Utils.bitmapToMat(b,rgba);
        Imgproc.cvtColor(rgba,out,Imgproc.COLOR_RGBA2GRAY);
        rgba.release();
        return out;
    }
    private static float bound(float n){return Math.max(0f,Math.min(1f,n));}

    public Match find(Bitmap scene,JSONArray referenceData) throws Exception {
        if(referenceData==null||referenceData.length()==0)return null;
        Mat sceneGrey=gray(scene),sceneDesc=new Mat();
        MatOfKeyPoint sceneKeys=new MatOfKeyPoint();
        ORB orb=ORB.create(1650);
        Mat empty=new Mat();
        DescriptorMatcher matcher=DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING);
        try{
            orb.detectAndCompute(sceneGrey,empty,sceneKeys,sceneDesc);
            if(sceneDesc.empty()||sceneKeys.rows()<9)return null;
            KeyPoint[] scenePoints=sceneKeys.toArray();
            Match best=null;
            for(int i=0;i<referenceData.length();i++){
                // Up to 8 different saved viewpoints; scenes need not resemble the backdrop.
                byte[] data=Base64.decode(referenceData.getString(i),Base64.DEFAULT);
                Bitmap picture=BitmapFactory.decodeByteArray(data,0,data.length);
                if(picture==null)continue;
                Mat ref=gray(picture);picture.recycle();
                Mat mask=Mat.zeros(ref.rows(),ref.cols(),CvType.CV_8UC1);
                // Center mask discards borders likely to contain the user's hand or room.
                int x=(int)(ref.cols()*.13),y=(int)(ref.rows()*.13);
                Imgproc.rectangle(mask,new Point(x,y),new Point(ref.cols()-x,ref.rows()-y),new Scalar(255),-1);
                MatOfKeyPoint refKeys=new MatOfKeyPoint();
                Mat refDesc=new Mat();
                try{
                    orb.detectAndCompute(ref,mask,refKeys,refDesc);
                    if(refDesc.empty()||refKeys.rows()<8)continue;
                    KeyPoint[] refPoints=refKeys.toArray();
                    List<MatOfDMatch> neighbors=new ArrayList<>();
                    matcher.knnMatch(refDesc,sceneDesc,neighbors,2);
                    List<Point> source=new ArrayList<>(),target=new ArrayList<>();
                    for(MatOfDMatch candidates:neighbors){
                        DMatch[] pair=candidates.toArray();
                        if(pair.length>=2 && pair[0].distance < pair[1].distance*.75f &&
                            pair[0].queryIdx<refPoints.length && pair[0].trainIdx<scenePoints.length){
                            source.add(refPoints[pair[0].queryIdx].pt);
                            target.add(scenePoints[pair[0].trainIdx].pt);
                        }
                        candidates.release();
                    }
                    if(source.size()<8)continue;
                    MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();
                    Mat inlierMask=new Mat(),transform=null;
                    MatOfPoint2f corners=new MatOfPoint2f(),projected=new MatOfPoint2f();
                    try {
                        src.fromList(source);dst.fromList(target);
                        transform=Calib3d.findHomography(src,dst,Calib3d.RANSAC,4,inlierMask);
                        if(transform.empty())continue;
                        int inliers=Core.countNonZero(inlierMask);
                        if(inliers<7 || inliers < source.size()*.40)continue;
                        // Only localize the center object region, not the whole photo.
                        float lx=ref.cols()*.14f,ly=ref.rows()*.14f;
                        corners.fromArray(new Point(lx,ly),new Point(ref.cols()-lx,ly),
                            new Point(ref.cols()-lx,ref.rows()-ly),new Point(lx,ref.rows()-ly));
                        Core.perspectiveTransform(corners,projected,transform);
                        Point[] pts=projected.toArray();
                        float left=Float.MAX_VALUE,top=Float.MAX_VALUE,right=-Float.MAX_VALUE,bottom=-Float.MAX_VALUE;
                        for(Point pt:pts) {
                            if(!Double.isFinite(pt.x)||!Double.isFinite(pt.y)) {left=Float.MAX_VALUE;break;}
                            left=Math.min(left,(float)pt.x);right=Math.max(right,(float)pt.x);
                            top=Math.min(top,(float)pt.y);bottom=Math.max(bottom,(float)pt.y);
                        }
                        if(left==Float.MAX_VALUE)continue;
                        float w=(right-left)/scene.getWidth(),h=(bottom-top)/scene.getHeight();
                        if(w<.016f||h<.016f||w>.85f||h>.85f)continue;
                        RectF region=new RectF(bound(left/scene.getWidth()),bound(top/scene.getHeight()),
                            bound(right/scene.getWidth()),bound(bottom/scene.getHeight()));
                        if(region.width()<=0||region.height()<=0)continue;
                        if(best==null||inliers>best.inliers)best=new Match(region,inliers,i+1);
                    }finally{
                        src.release();dst.release();inlierMask.release();
                        if(transform!=null)transform.release();
                        corners.release();projected.release();
                    }
                }finally{
                    ref.release();mask.release();refKeys.release();refDesc.release();
                }
            }
            if(best!=null)Log.i("PlainSight","Personal reference matched "+best.inliers+" geometric inliers");
            return best;
        }finally{
            sceneGrey.release();sceneDesc.release();sceneKeys.release();empty.release();
            orb.clear();matcher.clear();
        }
    }
}
