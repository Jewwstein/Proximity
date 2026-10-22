package com.echosight.app;

import android.media.Image;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.NotYetAvailableException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Camera feed + actual ARCore pose/depth/point capture in LIGHT.
 * Never continues positional tracking or generates points in dark memory mode.
 */
public final class ScanRenderer implements GLSurfaceView.Renderer {
    private static final String TAG="EchoSight-Scan";
    public interface Updates {
        void onScanFrame(int pointCount,int frames,boolean tracking,String detail);
    }
    private final SpatialMap map;
    private final Updates listener;
    private volatile Session session;
    private volatile boolean recording=false;
    private volatile boolean depthSupported=false;
    private volatile int displayRotation=0;
    private int texture=0,program=0,locationPosition=-1,locationTex=-1,locationSampler=-1;
    private int width=1,height=1,frameCount=0;
    private volatile boolean wideView=true;
    private long depthTimestamp=0,cloudTimestamp=0;
    private final FloatBuffer quad,texcoords;
    private final float[] vertex={-1,-1, 1,-1, -1,1, 1,1};
    public ScanRenderer(SpatialMap map,Updates callback){
        this.map=map;listener=callback;
        quad=buffer(vertex);
        texcoords=buffer(new float[8]);
    }
    private static FloatBuffer buffer(float[] data){
        ByteBuffer b=ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder());
        FloatBuffer fb=b.asFloatBuffer();fb.put(data);fb.position(0);return fb;
    }
    public void setSession(Session s,boolean supportsDepth){
        session=s;depthSupported=supportsDepth;depthTimestamp=0;cloudTimestamp=0;
        frameCount=0;
    }
    public void setRecording(boolean enable){recording=enable;}
    public void setWideView(boolean enabled){wideView=enabled;}
    public void setRotation(int rot){displayRotation=rot;}
    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
        GLES20.glClearColor(.015f,.05f,.085f,1);
        int[] generated=new int[1];
        GLES20.glGenTextures(1,generated,0);
        texture=generated[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        String vs="attribute vec4 p; attribute vec2 uv; varying vec2 t; void main(){gl_Position=p;t=uv;}";
        String fs="#extension GL_OES_EGL_image_external : require\n"+
            "precision mediump float; varying vec2 t; uniform samplerExternalOES cam;"+
            "void main(){gl_FragColor=texture2D(cam,t);}";
        int vert=shader(GLES20.GL_VERTEX_SHADER,vs);
        int frag=shader(GLES20.GL_FRAGMENT_SHADER,fs);
        program=GLES20.glCreateProgram();
        GLES20.glAttachShader(program,vert);
        GLES20.glAttachShader(program,frag);
        GLES20.glLinkProgram(program);
        locationPosition=GLES20.glGetAttribLocation(program,"p");
        locationTex=GLES20.glGetAttribLocation(program,"uv");
        locationSampler=GLES20.glGetUniformLocation(program,"cam");
    }
    private static int shader(int type,String code){
        int id=GLES20.glCreateShader(type);
        GLES20.glShaderSource(id,code);GLES20.glCompileShader(id);
        return id;
    }
    @Override public void onSurfaceChanged(GL10 gl,int w,int h){
        width=w;height=h;GLES20.glViewport(0,0,w,h);
    }
    @Override public void onDrawFrame(GL10 gl){
        GLES20.glViewport(0,0,width,height);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
        Session s=session;
        if(s==null || !recording)return;
        try{
            // The camera texture can only be bound on the GL render thread.
            s.setCameraTextureName(texture);
            // A 3:4 display instead of tall-phone center cropping gives a
            // wider visible field in small rooms. This does not change optics.
            int frameHeight=wideView?Math.min(height,Math.round(width*1.333333f)):height;
            int lower=(height-frameHeight)/2;
            s.setDisplayGeometry(displayRotation,width,frameHeight);
            Frame frame=s.update();
            GLES20.glViewport(0,lower,width,frameHeight);
            if(frame.getTimestamp()!=0)renderCamera(frame);
            frameCount++;
            Camera camera=frame.getCamera();
            if(camera.getTrackingState()!=TrackingState.TRACKING){
                if(frameCount%20==0)
                    listener.onScanFrame(map.count(),map.frames(),false,
                        "AR tracking paused: "+camera.getTrackingFailureReason());
                return;
            }
            if(frameCount%5==0){
                map.rememberPose(camera.getDisplayOrientedPose());
                capturePoints(frame);
                if(depthSupported && frameCount%10==0)captureDepth(frame,camera);
                if(frameCount%30==0)capturePlanes(s);
                if(frameCount%15==0)
                    listener.onScanFrame(map.count(),map.frames(),true,
                        depthSupported?"Depth + tracked feature map":"Tracked feature map (no Depth API)");
            }
        }catch(Throwable e){
            if(frameCount%30==0)Log.w(TAG,"Scan frame unavailable",e);
            if(frameCount%50==0)
                listener.onScanFrame(map.count(),map.frames(),false,
                    "AR scan: "+e.getClass().getSimpleName()+" "+e.getMessage());
            frameCount++;
        }
    }
    private void renderCamera(Frame f){
        FloatBuffer coords=buffer(vertex);
        texcoords.position(0);
        f.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            coords,Coordinates2d.TEXTURE_NORMALIZED,texcoords);
        texcoords.position(0);quad.position(0);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);
        GLES20.glUniform1i(locationSampler,0);
        GLES20.glEnableVertexAttribArray(locationPosition);
        GLES20.glEnableVertexAttribArray(locationTex);
        GLES20.glVertexAttribPointer(locationPosition,2,GLES20.GL_FLOAT,false,0,quad);
        GLES20.glVertexAttribPointer(locationTex,2,GLES20.GL_FLOAT,false,0,texcoords);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glDisableVertexAttribArray(locationPosition);
        GLES20.glDisableVertexAttribArray(locationTex);
    }
    private void capturePoints(Frame frame){
        try(PointCloud cloud=frame.acquirePointCloud()){
            if(cloud.getTimestamp()==cloudTimestamp)return;
            cloudTimestamp=cloud.getTimestamp();
            FloatBuffer b=cloud.getPoints();b.rewind();
            int all=b.remaining()/4;
            int skip=Math.max(1,all/1400);
            for(int i=0;i<all;i+=skip){
                int p=i*4;
                float confidence=b.get(p+3);
                if(confidence<.40f)continue;
                map.add(b.get(p),b.get(p+1),b.get(p+2));
            }
        }catch(Throwable e){Log.w(TAG,"Point sampling skipped",e);}
    }
    private void capturePlanes(Session session){
        // When the Depth API is unavailable, ARCore tracked horizontal and
        // vertical planes still contribute REAL detected planar boundaries.
        try{
            int segments=0;
            for(Plane plane:session.getAllTrackables(Plane.class)){
                if(plane.getTrackingState()!=TrackingState.TRACKING ||
                    plane.getSubsumedBy()!=null)continue;
                FloatBuffer polygon=plane.getPolygon();
                polygon.rewind();
                int n=polygon.remaining()/2;
                if(n<3)continue;
                Pose pose=plane.getCenterPose();
                float[] localA=new float[3],localB=new float[3];
                float[] start=new float[3],end=new float[3],last=new float[3],next=new float[3];
                for(int i=0;i<n && segments<380;i++){
                    int j=(i+1)%n;
                    localA[0]=polygon.get(i*2);
                    localA[1]=0;
                    localA[2]=polygon.get(i*2+1);
                    localB[0]=polygon.get(j*2);
                    localB[1]=0;
                    localB[2]=polygon.get(j*2+1);
                    pose.transformPoint(localA,0,start,0);
                    pose.transformPoint(localB,0,end,0);
                    float distance=(float)Math.sqrt(
                        (start[0]-end[0])*(start[0]-end[0])+
                        (start[1]-end[1])*(start[1]-end[1])+
                        (start[2]-end[2])*(start[2]-end[2]));
                    int steps=Math.max(1,Math.min(80,(int)Math.ceil(distance/.18)));
                    System.arraycopy(start,0,last,0,3);
                    for(int step=1;step<=steps && segments<380;step++){
                        float t=step/(float)steps;
                        for(int k=0;k<3;k++)next[k]=start[k]+t*(end[k]-start[k]);
                        map.add(last[0],last[1],last[2]);
                        map.add(next[0],next[1],next[2]);
                        map.addSurfaceEdge(last[0],last[1],last[2],
                            next[0],next[1],next[2]);
                        System.arraycopy(next,0,last,0,3);
                        segments++;
                    }
                }
            }
        }catch(Throwable e){Log.w(TAG,"Plane outline capture skipped",e);}
    }
    private void captureDepth(Frame frame,Camera camera){
        // Camera-space unprojection of sparse depth samples.
        // These are estimates, not safety-critical or a certified room mesh.
        try(Image depth=frame.acquireDepthImage16Bits()){
            if(depth.getTimestamp()==depthTimestamp)return;
            depthTimestamp=depth.getTimestamp();
            int dw=depth.getWidth(),dh=depth.getHeight();
            if(dw<8||dh<8)return;
            CameraIntrinsics K=camera.getTextureIntrinsics();
            int[] dimensions=K.getImageDimensions();
            float[] focal=K.getFocalLength(),principal=K.getPrincipalPoint();
            if(dimensions[0]<=0||dimensions[1]<=0)return;
            float fx=focal[0]*dw/dimensions[0];
            float fy=focal[1]*dh/dimensions[1];
            float cx=principal[0]*dw/dimensions[0];
            float cy=principal[1]*dh/dimensions[1];
            if(fx<=0||fy<=0)return;
            Image.Plane plane=depth.getPlanes()[0];
            ByteBuffer bytes=plane.getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int stride=plane.getRowStride(),pixelStride=plane.getPixelStride();
            Pose cameraPose=camera.getPose();
            float[] xyz=new float[3],world=new float[3];
            int step=Math.max(3,Math.min(dw,dh)/36);
            int cols=(dw-2*step+step-1)/step;
            float[] prevRow=new float[cols*3];
            boolean[] prevValid=new boolean[cols];
            float[] thisRow=new float[cols*3];
            boolean[] thisValid=new boolean[cols];
            for(int v=step;v<dh-step;v+=step){
                java.util.Arrays.fill(thisValid,false);
                int col=0;
                for(int u=step;u<dw-step;u+=step,col++){
                    int index=v*stride+u*pixelStride;
                    if(index+1>=bytes.limit())continue;
                    int mm=bytes.getShort(index)&0xffff;
                    if(mm<400||mm>5500)continue;
                    float z=mm/1000f;
                    xyz[0]=(u-cx)*z/fx;
                    xyz[1]=-(v-cy)*z/fy;
                    xyz[2]=-z;
                    cameraPose.transformPoint(xyz,0,world,0);
                    map.add(world[0],world[1],world[2]);
                    if(col<thisValid.length){
                        thisValid[col]=true;
                        thisRow[col*3]=world[0];thisRow[col*3+1]=world[1];thisRow[col*3+2]=world[2];
                        if(col>0 && thisValid[col-1]){
                            int p=(col-1)*3;
                            map.addSurfaceEdge(world[0],world[1],world[2],
                                thisRow[p],thisRow[p+1],thisRow[p+2]);
                        }
                        if(prevValid[col]){
                            int p=col*3;
                            map.addSurfaceEdge(world[0],world[1],world[2],
                                prevRow[p],prevRow[p+1],prevRow[p+2]);
                        }
                    }
                }
                float[] swap=prevRow;prevRow=thisRow;thisRow=swap;
                boolean[] validSwap=prevValid;prevValid=thisValid;thisValid=validSwap;
            }
        }catch(NotYetAvailableException ignored){
        }catch(Throwable e){Log.w(TAG,"Depth sampling skipped",e);}
    }
}
