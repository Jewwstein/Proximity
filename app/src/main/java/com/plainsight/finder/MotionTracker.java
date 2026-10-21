package com.plainsight.finder;

import android.graphics.Bitmap;
import android.graphics.RectF;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

import java.util.ArrayList;
import java.util.Arrays;

// Short-baseline optical flow. Repositions an already-detected selection on
// successive live camera frames; it does not claim to recognize object identity.
public final class MotionTracker implements AutoCloseable {
    private Mat previous;
    private MatOfPoint2f points;
    private RectF region;
    private int frameWidth,frameHeight;
    private int missed=0;
    private int framesSinceFeatures=0;

    private Mat gray(Bitmap b) {
        Mat rgba=new Mat(),grayscale=new Mat();
        Utils.bitmapToMat(b,rgba);
        Imgproc.cvtColor(rgba,grayscale,Imgproc.COLOR_RGBA2GRAY);
        rgba.release();
        return grayscale;
    }
    private static float limit(float value,float min,float max){return Math.max(min,Math.min(max,value));}
    private static float median(ArrayList<Float> values) {
        ArrayList<Float> copy=new ArrayList<>(values);
        copy.sort(Float::compareTo);
        return copy.get(copy.size()/2);
    }
    private void pickFeatures(Mat image){
        if(points!=null)points.release();
        Mat mask=Mat.zeros(image.rows(),image.cols(),CvType.CV_8UC1);
        MatOfPoint corners=new MatOfPoint();
        try {
            // OCR can find a single tiny digit: include part of its surroundings
            // so camera motion is trackable even when the digit has too few corners.
            float cx=region.centerX()*image.cols(), cy=region.centerY()*image.rows();
            float halfW=Math.max(image.cols()*.105f,region.width()*image.cols()*.87f);
            float halfH=Math.max(image.rows()*.095f,region.height()*image.rows()*.87f);
            int l=(int)limit(cx-halfW,0,image.cols()-1),r=(int)limit(cx+halfW,1,image.cols());
            int t=(int)limit(cy-halfH,0,image.rows()-1),b=(int)limit(cy+halfH,1,image.rows());
            if(r<=l||b<=t){points=new MatOfPoint2f();return;}
            Imgproc.rectangle(mask,new Point(l,t),new Point(r,b),new org.opencv.core.Scalar(255),-1);
            Imgproc.goodFeaturesToTrack(image,corners,64,.012,7,mask);
            Point[] raw=corners.toArray();
            points=new MatOfPoint2f(raw);
        } finally {
            mask.release();
            corners.release();
        }
    }
    public synchronized boolean begin(Bitmap frame,RectF selected) {
        clear();
        if(frame==null||selected==null||selected.isEmpty())return false;
        previous=gray(frame);
        frameWidth=frame.getWidth();frameHeight=frame.getHeight();
        region=new RectF(selected);
        pickFeatures(previous);
        missed=0;framesSinceFeatures=0;
        return points!=null && points.rows()>=5;
    }
    public synchronized RectF advance(Bitmap frame) {
        if(frame==null||previous==null||region==null)return null;
        if(frame.getWidth()!=frameWidth||frame.getHeight()!=frameHeight)return null;
        Mat next=gray(frame);
        MatOfPoint2f updated=new MatOfPoint2f();
        MatOfByte flags=new MatOfByte();
        MatOfFloat errors=new MatOfFloat();
        try {
            if(points==null||points.rows()<5)return null;
            Video.calcOpticalFlowPyrLK(previous,next,points,updated,flags,errors,new Size(17,17),2);
            Point[] oldPts=points.toArray(), newPts=updated.toArray();
            byte[] good=flags.toArray();
            ArrayList<Float> dxs=new ArrayList<>(),dys=new ArrayList<>();
            for(int i=0;i<Math.min(Math.min(oldPts.length,newPts.length),good.length);i++){
                if(good[i]==0)continue;
                float dx=(float)(newPts[i].x-oldPts[i].x);
                float dy=(float)(newPts[i].y-oldPts[i].y);
                if(Math.abs(dx)>frameWidth*.3 || Math.abs(dy)>frameHeight*.3)continue;
                dxs.add(dx);dys.add(dy);
            }
            if(dxs.size()<5){
                missed++;
                if(missed>=2)return null;
                return new RectF(region);
            }
            float mdx=median(dxs),mdy=median(dys);
            int consistent=0;
            for(int i=0;i<dxs.size();i++){
                if(Math.abs(dxs.get(i)-mdx)<12 && Math.abs(dys.get(i)-mdy)<12)consistent++;
            }
            if(consistent<5 || consistent<dxs.size()*.48) return null;
            float fx=mdx/frameWidth,fy=mdy/frameHeight;
            region.offset(fx,fy);
            if(region.right<0||region.bottom<0||region.left>1||region.top>1)return null;
            region.left=limit(region.left,0,1);
            region.right=limit(region.right,0,1);
            region.top=limit(region.top,0,1);
            region.bottom=limit(region.bottom,0,1);
            if(region.width()<.003f||region.height()<.003f)return null;
            missed=0;
            previous.release();
            previous=next;
            next=null;
            framesSinceFeatures++;
            if(framesSinceFeatures>=4){
                pickFeatures(previous);framesSinceFeatures=0;
            }else{
                ArrayList<Point> tracked=new ArrayList<>();
                for(int i=0;i<Math.min(newPts.length,good.length);i++){
                    if(good[i]!=0)tracked.add(newPts[i]);
                }
                if(points!=null)points.release();
                points=new MatOfPoint2f();
                points.fromList(tracked);
            }
            return new RectF(region);
        }finally {
            if(next!=null)next.release();
            updated.release();flags.release();errors.release();
        }
    }
    public synchronized void clear(){
        if(previous!=null){previous.release();previous=null;}
        if(points!=null){points.release();points=null;}
        region=null;missed=0;framesSinceFeatures=0;
    }
    @Override public void close(){clear();}
}
