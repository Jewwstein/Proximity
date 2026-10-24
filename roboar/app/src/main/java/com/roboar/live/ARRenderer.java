package com.roboar.live;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;

public class ARRenderer implements GLSurfaceView.Renderer {
    private final MainActivity activity;
    private int textureId = -1, cameraProgram, boxProgram;
    private int viewportWidth=1,viewportHeight=1;
    private FloatBuffer square, texCoords, cube;
    private Anchor anchor;
    private volatile boolean resetRequested = false, bindingNeeded = true;
    private volatile float tapX = -1, tapY = -1;
    private volatile boolean robotTarget = false;
    private float morph = 0, lastSeconds = 0;
    private volatile float worldScale=1.0f;
    private volatile float[] paint = {0.13f,0.42f,0.98f};
    private final float[] proj=new float[16],view=new float[16],anchorMatrix=new float[16];
    private final float[] base=new float[16], model=new float[16], temp=new float[16], mv=new float[16], mvp=new float[16];
    private static final float[] QUAD = {-1,-1,1,-1,-1,1,1,1};
    private static final float[] BOX = {
        -.5f,-.5f,.5f, .5f,-.5f,.5f, .5f,.5f,.5f,  -.5f,-.5f,.5f, .5f,.5f,.5f, -.5f,.5f,.5f,
        .5f,-.5f,-.5f, -.5f,-.5f,-.5f, -.5f,.5f,-.5f,  .5f,-.5f,-.5f, -.5f,.5f,-.5f, .5f,.5f,-.5f,
        -.5f,-.5f,-.5f, -.5f,-.5f,.5f, -.5f,.5f,.5f,  -.5f,-.5f,-.5f, -.5f,.5f,.5f, -.5f,.5f,-.5f,
        .5f,-.5f,.5f, .5f,-.5f,-.5f, .5f,.5f,-.5f,  .5f,-.5f,.5f, .5f,.5f,-.5f, .5f,.5f,.5f,
        -.5f,.5f,.5f, .5f,.5f,.5f, .5f,.5f,-.5f,  -.5f,.5f,.5f, .5f,.5f,-.5f, -.5f,.5f,-.5f,
        -.5f,-.5f,-.5f, .5f,-.5f,-.5f, .5f,-.5f,.5f,  -.5f,-.5f,-.5f, .5f,-.5f,.5f, -.5f,-.5f,.5f
    };
    ARRenderer(MainActivity activity){this.activity=activity;}
    private static FloatBuffer buffer(float[] data){
        FloatBuffer b=ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data);b.position(0);return b;
    }
    private static int shader(int type,String src) {
        int id=GLES20.glCreateShader(type);
        GLES20.glShaderSource(id,src); GLES20.glCompileShader(id);
        int[] ok=new int[1]; GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0) throw new RuntimeException("Shader compile: "+GLES20.glGetShaderInfoLog(id));
        return id;
    }
    private static int program(String vs,String fs){
        int p=GLES20.glCreateProgram();
        GLES20.glAttachShader(p,shader(GLES20.GL_VERTEX_SHADER,vs));
        GLES20.glAttachShader(p,shader(GLES20.GL_FRAGMENT_SHADER,fs));
        GLES20.glLinkProgram(p);
        int[] ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0)throw new RuntimeException("Shader link: "+GLES20.glGetProgramInfoLog(p));
        return p;
    }
    @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,javax.microedition.khronos.egl.EGLConfig config){
        GLES20.glClearColor(0,0,0,1);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        square=buffer(QUAD);cube=buffer(BOX);
        texCoords=buffer(new float[8]);
        String bgvs="attribute vec2 aPos; attribute vec2 aUV; varying vec2 vUV; void main(){vUV=aUV;gl_Position=vec4(aPos,0.0,1.0);}";
        String bgfs="#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 vUV;uniform samplerExternalOES uCam;void main(){gl_FragColor=texture2D(uCam,vUV);}";
        cameraProgram=program(bgvs,bgfs);
        String vs="uniform mat4 uMVP;attribute vec3 aPos;void main(){gl_Position=uMVP*vec4(aPos,1.0);}";
        String fs="precision mediump float;uniform vec4 uColor;void main(){gl_FragColor=uColor;}";
        boxProgram=program(vs,fs);
        int[] textures=new int[1];GLES20.glGenTextures(1,textures,0);textureId=textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        bindingNeeded=true;
    }
    @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl,int w,int h){
        viewportWidth=w;viewportHeight=h;GLES20.glViewport(0,0,w,h);
    }
    @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl){
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
        Session session=activity.getArSession();
        if(session==null || textureId<0) return;
        try{
            if(bindingNeeded){session.setCameraTextureName(textureId);bindingNeeded=false;}
            session.setDisplayGeometry(activity.getDisplayRotation(),viewportWidth,viewportHeight);
            Frame frame=session.update();
            if(frame.getTimestamp()!=0) {
                updateTex(frame);
                renderBackground();
            }
            if(resetRequested){
                if(anchor!=null){anchor.detach();anchor=null;}
                morph=0;robotTarget=false;resetRequested=false;
                activity.message("Tap a patch of ground near your car to place the hologram.");
            }
            Camera camera=frame.getCamera();
            if(camera.getTrackingState()!=TrackingState.TRACKING) return;
            if(tapX>=0){
                float x=tapX,y=tapY;tapX=-1;tapY=-1;
                for(HitResult hit:frame.hitTest(x,y)) {
                    Trackable track=hit.getTrackable();
                    if(track instanceof Plane && ((Plane)track).getType()==Plane.Type.HORIZONTAL_UPWARD_FACING &&
                            ((Plane)track).isPoseInPolygon(hit.getHitPose())){
                        if(anchor!=null)anchor.detach();
                        anchor=hit.createAnchor();
                        morph=0; robotTarget=false;
                        activity.message("Car anchored. Press TRANSFORM and walk around it.");
                        break;
                    }
                }
            }
            if(anchor==null || anchor.getTrackingState()!=TrackingState.TRACKING)return;
            camera.getProjectionMatrix(proj,0,0.1f,100.0f);
            camera.getViewMatrix(view,0);
            anchor.getPose().toMatrix(anchorMatrix,0);
            Matrix.setIdentityM(base,0);
            Matrix.multiplyMM(base,0,anchorMatrix,0,identityScale(worldScale),0);
            float sec=System.nanoTime()/1000000000f;
            float dt=lastSeconds==0?0.016f:Math.min(0.05f,Math.max(0,sec-lastSeconds));
            lastSeconds=sec;
            float desired=robotTarget?1f:0f;
            float delta=dt/2.8f;
            morph += Math.max(-delta,Math.min(delta,desired-morph));
            float t=morph*morph*(3f-2f*morph);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            drawMech(t);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        } catch(Exception err){
            activity.message("AR error: "+err.getClass().getSimpleName()+" • "+err.getMessage());
        }
    }
    private float[] identityScale(float s){
        float[] a=new float[16];Matrix.setIdentityM(a,0);Matrix.scaleM(a,0,s,s,s);return a;
    }
    private void updateTex(Frame frame) {
        FloatBuffer p=buffer(QUAD);
        FloatBuffer uv=buffer(new float[8]);
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,p,Coordinates2d.TEXTURE_NORMALIZED,uv);
        uv.position(0);texCoords=uv;
    }
    private void renderBackground(){
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(cameraProgram);
        int pos=GLES20.glGetAttribLocation(cameraProgram,"aPos");
        int uv=GLES20.glGetAttribLocation(cameraProgram,"aUV");
        GLES20.glEnableVertexAttribArray(pos);GLES20.glEnableVertexAttribArray(uv);
        square.position(0);texCoords.position(0);
        GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,square);
        GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,0,texCoords);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram,"uCam"),0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glDisableVertexAttribArray(pos);GLES20.glDisableVertexAttribArray(uv);
    }
    // Each piece moves between a plausible automobile position and a humanoid-robot position.
    private void piece(float t,int mat,
        float ax,float ay,float az,float aw,float ah,float ad,
        float bx,float by,float bz,float bw,float bh,float bd) {
        float x=lerp(ax,bx,t),y=lerp(ay,by,t),z=lerp(az,bz,t);
        float w=lerp(aw,bw,t),h=lerp(ah,bh,t),d=lerp(ad,bd,t);
        Matrix.setIdentityM(model,0);
        Matrix.translateM(model,0,x,y,z);
        Matrix.scaleM(model,0,w,h,d);
        Matrix.multiplyMM(temp,0,base,0,model,0);
        Matrix.multiplyMM(mv,0,view,0,temp,0);
        Matrix.multiplyMM(mvp,0,proj,0,mv,0);
        GLES20.glUseProgram(boxProgram);
        int m=GLES20.glGetUniformLocation(boxProgram,"uMVP");
        GLES20.glUniformMatrix4fv(m,1,false,mvp,0);
        float[] c=mat==0?paint:mat==1?new float[]{0.08f,0.10f,0.15f}:mat==2?new float[]{0.15f,0.28f,0.33f}:mat==3?new float[]{0.96f,0.81f,0.26f}:new float[]{0.59f,0.67f,0.76f};
        GLES20.glUniform4f(GLES20.glGetUniformLocation(boxProgram,"uColor"),c[0],c[1],c[2],1);
        int pos=GLES20.glGetAttribLocation(boxProgram,"aPos");
        GLES20.glEnableVertexAttribArray(pos);
        cube.position(0);
        GLES20.glVertexAttribPointer(pos,3,GLES20.GL_FLOAT,false,0,cube);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,36);
        GLES20.glDisableVertexAttribArray(pos);
    }
    private static float lerp(float a,float b,float t){return a+(b-a)*t;}
    private void drawMech(float t){
        piece(t,1, 0,.58,0, 1.65,.32,3.65,  0,1.96,.04,1.28,.76,.72);
        piece(t,0, 0,.82,-1.23, 1.8,.28,1.53,  0,3.25,-.17,1.72,1.12,.78);
        piece(t,0, 0,1.18,.42, 1.46,.57,1.55,  0,3.43,.31,1.43,.62,.43);
        piece(t,0, 0,1.52,.46, 1.44,.13,1.3,  0,4.4,0,.69,.67,.61);
        piece(t,2, 0,1.23,-.3, 1.4,.5,.18,  0,3.31,-.61,1.14,.60,.07);
        piece(t,2, -.78,1.08,.42, .08,.55,1.28, -1.28,3.48,0,.58,.59,.7);
        piece(t,2, .78,1.08,.42, .08,.55,1.28, 1.28,3.48,0,.58,.59,.7);
        piece(t,0, -1.02,.66,.15, .12,.48,1.27, -1.31,2.65,0,.46,1.06,.52);
        piece(t,0, 1.02,.66,.15, .12,.48,1.27, 1.31,2.65,0,.46,1.06,.52);
        piece(t,4, -.57,.5,.97, .60,.30,1.7, -.41,1.36,.12,.59,1.76,.66);
        piece(t,4, .57,.5,.97, .60,.30,1.7, .41,1.36,.12,.59,1.76,.66);
        piece(t,1, -.58,.22,-.95, .6,.18,.79, -.41,.23,-.30,.70,.32,1.10);
        piece(t,1, .58,.22,-.95, .6,.18,.79, .41,.23,-.30,.70,.32,1.10);
        piece(t,1, -.96,.37,-1.28, .23,.70,.70, -.97,2.25,.36,.36,.68,.40);
        piece(t,1, .96,.37,-1.28, .23,.70,.70, .97,2.25,.36,.36,.68,.40);
        piece(t,1, -.96,.37,1.28, .23,.70,.70, -.40,.98,.32,.25,.65,.48);
        piece(t,1, .96,.37,1.28, .23,.70,.70, .40,.98,.32,.25,.65,.48);
        piece(t,3, -.6,.85,-2.02, .43,.16,.10, -.23,4.4,-.33,.21,.14,.06);
        piece(t,3, .6,.85,-2.02, .43,.16,.10, .23,4.4,-.33,.21,.14,.06);
        piece(t,3, -.60,.78,2.05, .43,.16,.10, -.28,3.03,-.58,.35,.11,.08);
        piece(t,3, .60,.78,2.05, .43,.16,.10, .28,3.03,-.58,.35,.11,.08);
    }
    public void placeAt(float x,float y){tapX=x;tapY=y;}
    public boolean toggleTransform(){robotTarget=!robotTarget;return robotTarget;}
    public void setColor(float[] c){paint=c.clone();}
    public void scaleBy(float f){worldScale=Math.max(0.25f,Math.min(2.5f,worldScale*f));}
    public void reset(){resetRequested=true;tapX=-1;}
    public void invalidateCameraTextureBinding(){bindingNeeded=true;}
}
