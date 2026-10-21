package com.plainsight.finder;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

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
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

public final class PersonalImageFinder {
    public static final class Match {
        public final RectF box;
        public final double score;
        public final String mode;
        Match(RectF b,double s,String m){box=b;score=s;mode=m;}
    }
    private static final String TAG="PlainSightVision";
    private static boolean loaded=false;
    public PersonalImageFinder(){
        synchronized(PersonalImageFinder.class){
            if(!loaded){System.loadLibrary(Core.NATIVE_LIBRARY_NAME);loaded=true;}
        }
    }
    private static Mat grayscale(Bitmap b,int maxDim) {
        Mat rgba=new Mat(),gray=new Mat();
        Utils.bitmapToMat(b,rgba);
        Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);
        rgba.release();
        if(Math.max(gray.cols(),gray.rows())>maxDim){
            double f=maxDim/(double)Math.max(gray.cols(),gray.rows());
            Mat small=new Mat();
            Imgproc.resize(gray,small,new Size(),f,f,Imgproc.INTER_AREA);
            gray.release();return small;
        }
        return gray;
    }
    private static RectF region(int x,int y,int width,int height,Mat scene) {
        return new RectF(
            Math.max(0,x/(float)scene.cols()),
            Math.max(0,y/(float)scene.rows()),
            Math.min(1,(x+width)/(float)scene.cols()),
            Math.min(1,(y+height)/(float)scene.rows())
        );
    }
    private static RectF checked(RectF b) {
        if(b.left<0||b.top<0||b.right>1||b.bottom>1||
           b.width()<.018f||b.height()<.018f||b.width()>.96f||b.height()>.96f)return null;
        return b;
    }
    private Match orbFind(Mat scene,Mat reference,ORB orb,MatOfKeyPoint sceneKp,Mat sceneDesc){
        MatOfKeyPoint refKp=new MatOfKeyPoint();
        Mat desc=new Mat();
        try{
            orb.detectAndCompute(reference,new Mat(),refKp,desc);
            if(desc.empty()||sceneDesc.empty()||desc.rows()<7)return null;
            BFMatcher bf=BFMatcher.create(Core.NORM_HAMMING,false);
            List<MatOfDMatch> candidates=new ArrayList<>();
            bf.knnMatch(desc,sceneDesc,candidates,2);
            KeyPoint[] refPoints=refKp.toArray();
            KeyPoint[] scenePoints=sceneKp.toArray();
            List<Point> src=new ArrayList<>(),dst=new ArrayList<>();
            for(MatOfDMatch pair:candidates){
                DMatch[] v=pair.toArray();
                if(v.length>=2&&v[0].distance<.73*v[1].distance&&
                    v[0].queryIdx<refPoints.length&&v[0].trainIdx<scenePoints.length){
                    src.add(refPoints[v[0].queryIdx].pt);
                    dst.add(scenePoints[v[0].trainIdx].pt);
                }
                pair.release();
            }
            if(src.size()<7)return null;
            MatOfPoint2f from=new MatOfPoint2f(),to=new MatOfPoint2f();
            from.fromList(src);to.fromList(dst);
            Mat mask=new Mat();
            Mat H=Calib3d.findHomography(from,to,Calib3d.RANSAC,3,mask);
            int inliers=mask.empty()?0:Core.countNonZero(mask);
            from.release();to.release();mask.release();
            if(H.empty()||inliers<6){H.release();return null;}
            MatOfPoint2f corners=new MatOfPoint2f(new Point(0,0),
                new Point(reference.cols(),0),new Point(reference.cols(),reference.rows()),
                new Point(0,reference.rows()));
            MatOfPoint2f result=new MatOfPoint2f();
            Core.perspectiveTransform(corners,result,H);
            Point[] p=result.toArray();
            corners.release();result.release();H.release();
            float left=Float.MAX_VALUE,top=Float.MAX_VALUE,right=0,bottom=0;
            for(Point pt:p){
                left=(float)Math.min(left,pt.x);top=(float)Math.min(top,pt.y);
                right=(float)Math.max(right,pt.x);bottom=(float)Math.max(bottom,pt.y);
            }
            RectF b=checked(new RectF(left/scene.cols(),top/scene.rows(),
                right/scene.cols(),bottom/scene.rows()));
            if(b==null)return null;
            return new Match(b,Math.min(.99,inliers/(double)(src.size()+2)),"features");
        }catch(Exception e){
            Log.w(TAG,"ORB match rejected",e);return null;
        }finally{refKp.release();desc.release();}
    }
    private Match templates(Mat scene,Mat ref) {
        if(scene.empty()||ref.empty())return null;
        Match best=null;
        int shortest=Math.min(scene.cols(),scene.rows());
        for(double fraction:new double[]{.10,.15,.21,.29,.39,.52}){
            int side=Math.max(18,(int)Math.round(shortest*fraction));
            if(side>=scene.cols()||side>=scene.rows())continue;
            Mat needle=new Mat(),scores=new Mat();
            try{
                Imgproc.resize(ref,needle,new Size(side,side));
                Core.MinMaxLocResult stats;
                Imgproc.matchTemplate(scene,needle,scores,Imgproc.TM_CCOEFF_NORMED);
                stats=Core.minMaxLoc(scores);
                if(Double.isNaN(stats.maxVal))continue;
                if(best==null||stats.maxVal>best.score){
                    RectF region=checked(region((int)stats.maxLoc.x,(int)stats.maxLoc.y,side,side,scene));
                    if(region!=null)best=new Match(region,stats.maxVal,"appearance");
                }
            }finally{needle.release();scores.release();}
        }
        return best;
    }
    public Match findPersonal(Bitmap sceneBitmap,List<Bitmap> references){
        Mat scene=grayscale(sceneBitmap,720);
        ORB orb=ORB.create(750);
        MatOfKeyPoint skp=new MatOfKeyPoint();
        Mat sdesc=new Mat();
        Match best=null;
        try{
            orb.detectAndCompute(scene,new Mat(),skp,sdesc);
            for(Bitmap photo:references){
                if(photo==null||photo.isRecycled())continue;
                Mat ref=grayscale(photo,450);
                try{
                    // The item is centered in a known training guide. Exclude outer background.
                    int s=Math.round(Math.min(ref.cols(),ref.rows())*.73f);
                    org.opencv.core.Rect clip=new org.opencv.core.Rect(
                        (ref.cols()-s)/2,(ref.rows()-s)/2,s,s);
                    Mat content=ref.submat(clip);
                    try{
                        Match orbHit=orbFind(scene,content,orb,skp,sdesc);
                        if(orbHit!=null&&orbHit.score>.32)return orbHit;
                        Match appearance=templates(scene,content);
                        if(appearance!=null&&(best==null||appearance.score>best.score))best=appearance;
                    }finally{content.release();}
                }finally{ref.release();}
            }
        }finally{skp.release();sdesc.release();scene.release();}
        if(best!=null&&best.score>=.73)return best;
        return null;
    }
    public Match track(Bitmap frame,Bitmap template,RectF previousBox){
        if(template==null||template.isRecycled()||previousBox==null)return null;
        Mat scene=grayscale(frame,660);
        Mat ref=grayscale(template,300);
        try{
            int w=scene.cols(),h=scene.rows();
            int oldL=Math.round(previousBox.left*w),oldT=Math.round(previousBox.top*h);
            int oldW=Math.max(16,Math.round(previousBox.width()*w));
            int oldH=Math.max(16,Math.round(previousBox.height()*h));
            int xl=Math.max(0,oldL-oldW),yt=Math.max(0,oldT-oldH);
            int xr=Math.min(w,oldL+oldW*2),yb=Math.min(h,oldT+oldH*2);
            if(xr-xl<25||yb-yt<25)return null;
            Mat area=scene.submat(new org.opencv.core.Rect(xl,yt,xr-xl,yb-yt));
            Match best=null;
            try{
                for(float scale:new float[]{.83f,1f,1.18f}){
                    int cw=Math.round(oldW*scale),ch=Math.round(oldH*scale);
                    if(cw<9||ch<9||cw>=area.cols()||ch>=area.rows())continue;
                    Mat needle=new Mat(),corr=new Mat();
                    try{
                        Imgproc.resize(ref,needle,new Size(cw,ch));
                        Imgproc.matchTemplate(area,needle,corr,Imgproc.TM_CCOEFF_NORMED);
                        Core.MinMaxLocResult r=Core.minMaxLoc(corr);
                        if(Double.isNaN(r.maxVal))continue;
                        RectF b=region(xl+(int)r.maxLoc.x,yt+(int)r.maxLoc.y,cw,ch,scene);
                        if(checked(b)!=null &&(best==null||r.maxVal>best.score))
                            best=new Match(b,r.maxVal,"tracking");
                    }finally{needle.release();corr.release();}
                }
            }finally{area.release();}
            return best!=null&&best.score>=.65?best:null;
        }finally{scene.release();ref.release();}
    }
}
