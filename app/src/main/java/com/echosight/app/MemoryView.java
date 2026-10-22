package com.echosight.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.View;
import java.util.Arrays;
import java.util.Locale;

/**
 * Draws measured world points and short real-depth edge segments, NOT guessed
 * walls. Optional step-based position is EXPERIMENTAL inertial dead reckoning.
 * No relocalization, no obstacle awareness, no safe navigation guarantee.
 */
public final class MemoryView extends View implements SensorEventListener {
    public interface StepUpdates { void changed(int steps,float meters,float uncertainty); }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SensorManager sensors;
    private final Sensor orientationSensor,stepSensor;
    private final float[] sensorNow=new float[9],sensorStart=new float[9];
    private float[] points=new float[0],edges=new float[0];
    private float[] cameraLocation={0,0,0},startLocation={0,0,0};
    private float[] cameraQuaternion={0,0,0,1};
    private boolean startKnown=false,sensorKnown=false,enabled=false,walking=false;
    private boolean baselineKnown=false,stepCap=false;
    private float stepBaseline=0,stepLength=.63f;
    private float manualYaw=0,manualPitch=0,travel=0;
    private long lastRender=0,lastStepNanos=0;
    private int steps=0,style=0;
    private StepUpdates stepUpdates;

    public MemoryView(Context c){
        super(c);
        sensors=(SensorManager)c.getSystemService(Context.SENSOR_SERVICE);
        Sensor game=sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        orientationSensor=game!=null?game:
            sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        Sensor detector=sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        stepSensor=detector!=null?detector:
            sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
    }
    public void setStepUpdates(StepUpdates callbacks){stepUpdates=callbacks;}
    public boolean hasStepSensor(){return stepSensor!=null;}
    public boolean hasDirectionSensor(){return orientationSensor!=null;}
    public int stepCount(){return steps;}
    public float stepDistance(){return travel;}
    public float uncertainty(){return .35f + steps*.11f+steps*steps*.0035f;}
    public boolean isWalking(){return walking;}
    public void setStepLength(float meters){stepLength=Math.max(.35f,Math.min(1.10f,meters));}
    public int surfaceCount(){return edges.length/6;}
    public void setStyle(int s){style=(s%3+3)%3;invalidate();}
    public int getStyle(){return style;}
    public void activate(SpatialMap map){
        enabled=true;
        points=map.snapshot();
        edges=map.surfaceSnapshot();
        startLocation=map.position();
        cameraQuaternion=map.orientation();
        walking=false;stepCap=false;
        steps=0;travel=0;baselineKnown=false;lastStepNanos=0;
        cameraLocation=startLocation.clone();
        manualYaw=0;manualPitch=0;startKnown=false;sensorKnown=false;
        if(sensors!=null)sensors.unregisterListener(this);
        if(sensors!=null&&orientationSensor!=null)
            sensors.registerListener(this,orientationSensor,SensorManager.SENSOR_DELAY_GAME);
        invalidate();
    }
    public void deactivate(){
        enabled=false;walking=false;
        if(sensors!=null)sensors.unregisterListener(this);
    }
    /** Explicitly opt into dead reckoning; caller must first get ACTIVITY_RECOGNITION. */
    public boolean setWalking(boolean enabledMode){
        if(!enabled)return false;
        if(!enabledMode){
            walking=false;
            if(sensors!=null&&stepSensor!=null)sensors.unregisterListener(this,stepSensor);
            invalidate();return true;
        }
        if(stepSensor==null||sensors==null)return false;
        if(walking)return true;
        // The mapped position is the final CAMERA location of the last scan,
        // NOT the user's automatically detected current location.
        walking=sensors.registerListener(this,stepSensor,SensorManager.SENSOR_DELAY_NORMAL);
        baselineKnown=false;stepCap=false;lastStepNanos=0;
        invalidate();
        return walking;
    }
    public void resetWalking(){
        steps=0;travel=0;baselineKnown=false;stepCap=false;lastStepNanos=0;
        cameraLocation=startLocation.clone();
        if(stepUpdates!=null)stepUpdates.changed(steps,travel,uncertainty());
        invalidate();
    }
    @Override public void onSensorChanged(SensorEvent e){
        if(!enabled)return;
        if(e.sensor==orientationSensor){
            synchronized(sensorNow){
                SensorManager.getRotationMatrixFromVector(sensorNow,e.values);
                if(!startKnown){System.arraycopy(sensorNow,0,sensorStart,0,9);startKnown=true;}
                sensorKnown=true;
            }
            if(System.currentTimeMillis()-lastRender>65){
                lastRender=System.currentTimeMillis();invalidate();
            }
            return;
        }
        if(e.sensor!=stepSensor||!walking||stepCap)return;
        int increase=0;
        if(stepSensor.getType()==Sensor.TYPE_STEP_DETECTOR){
            if(e.timestamp-lastStepNanos>245_000_000L)increase=1;
        }else {
            if(!baselineKnown){stepBaseline=e.values[0];baselineKnown=true;return;}
            int delta=Math.round(e.values[0]-stepBaseline);
            increase=Math.max(0,Math.min(3,delta));
            stepBaseline=e.values[0];
        }
        if(increase<=0)return;
        lastStepNanos=e.timestamp;
        // Phone pointed forward = assumed travel direction, NOT verified.
        float[] orientation=worldCameraRotation();
        float dx=-orientation[2],dz=-orientation[8];
        float norm=(float)Math.hypot(dx,dz);
        if(norm<.01f)return;
        dx/=norm;dz/=norm;
        for(int i=0;i<increase;i++){
            if(steps>=24){stepCap=true;walking=false;break;}
            cameraLocation[0]+=dx*stepLength;
            cameraLocation[2]+=dz*stepLength;
            steps++;travel+=stepLength;
        }
        if(stepCap&&sensors!=null)sensors.unregisterListener(this,stepSensor);
        if(stepUpdates!=null)stepUpdates.changed(steps,travel,uncertainty());
        invalidate();
    }
    @Override public void onAccuracyChanged(Sensor s,int accuracy){}
    public void turnVirtual(float dx,float dy){
        manualYaw+=dx*.004f;
        manualPitch=Math.max(-.85f,Math.min(.85f,manualPitch+dy*.004f));
        invalidate();
    }
    private static void multiply(float[] a,float[] b,float[] out){
        for(int row=0;row<3;row++)
            for(int col=0;col<3;col++){
                float sum=0;
                for(int k=0;k<3;k++)sum+=a[row*3+k]*b[k*3+col];
                out[row*3+col]=sum;
            }
    }
    private static void transpose(float[] in,float[] out){
        for(int r=0;r<3;r++)for(int c=0;c<3;c++)out[r*3+c]=in[c*3+r];
    }
    private static float[] quaternionMatrix(float[] q){
        float x=q[0],y=q[1],z=q[2],w=q[3];
        return new float[]{
            1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w),
            2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w),
            2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)
        };
    }
    private float[] worldCameraRotation(){
        float[] base=quaternionMatrix(cameraQuaternion);
        float[] delta=new float[9];delta[0]=delta[4]=delta[8]=1f;
        if(sensorKnown&&startKnown){
            float[] now=new float[9],start=new float[9],startT=new float[9];
            synchronized(sensorNow){
                System.arraycopy(sensorNow,0,now,0,9);
                System.arraycopy(sensorStart,0,start,0,9);
            }
            transpose(start,startT);multiply(startT,now,delta);
        }
        if(Math.abs(manualYaw)+Math.abs(manualPitch)>.0001f){
            float cy=(float)Math.cos(manualYaw),sy=(float)Math.sin(manualYaw);
            float cp=(float)Math.cos(manualPitch),sp=(float)Math.sin(manualPitch);
            float[] yaw={cy,0,sy,0,1,0,-sy,0,cy};
            float[] pitch={1,0,0,0,cp,-sp,0,sp,cp};
            float[] combined=new float[9],tmp=new float[9];
            multiply(yaw,pitch,tmp);multiply(delta,tmp,combined);delta=combined;
        }
        float[] world=new float[9];multiply(base,delta,world);
        return world;
    }
    private float[] project(float x,float y,float z,float[] orientation,
                            float centerX,float centerY,float focal){
        float dx=x-cameraLocation[0],dy=y-cameraLocation[1],dz=z-cameraLocation[2];
        float cx=orientation[0]*dx+orientation[3]*dy+orientation[6]*dz;
        float cy=orientation[1]*dx+orientation[4]*dy+orientation[7]*dz;
        float cz=orientation[2]*dx+orientation[5]*dy+orientation[8]*dz;
        float range=-cz;
        if(range<.25f||range>16f)return null;
        return new float[]{centerX+focal*cx/range,centerY-focal*cy/range,range};
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        canvas.drawColor(0xff020b14);
        if(!enabled)return;
        int w=getWidth(),h=getHeight();
        if(w<20||h<20)return;
        float[] orientation=worldCameraRotation();
        float cx=w*.5f,cy=h*.5f,focal=w*.78f;
        int drawn=0;
        // True depth continuity segments: only connect 3D neighboring samples
        // originally from the same actual depth frame; do not synthesize walls.
        if(style!=1){
            int lineStride=Math.max(1,(edges.length/6)/15000);
            paint.setStrokeWidth(1.6f*getResources().getDisplayMetrics().density);
            paint.setStyle(Paint.Style.STROKE);
            for(int i=0;i<edges.length;i+=6*lineStride){
                float[] a=project(edges[i],edges[i+1],edges[i+2],orientation,cx,cy,focal);
                float[] b=project(edges[i+3],edges[i+4],edges[i+5],orientation,cx,cy,focal);
                if(a==null||b==null)continue;
                if(Math.abs(a[0]-b[0])+Math.abs(a[1]-b[1])>w*.28f)continue;
                if((a[0]<-10&&b[0]<-10)||(a[0]>w+10&&b[0]>w+10)||
                    (a[1]<-10&&b[1]<-10)||(a[1]>h+10&&b[1]>h+10))continue;
                int alpha=(int)Math.max(35,Math.min(180,220-(a[2]+b[2])*11));
                paint.setColor(Color.argb(alpha,48,229,248));
                canvas.drawLine(a[0],a[1],b[0],b[1],paint);
                drawn++;
            }
        }
        int dots=0;
        if(style!=0||edges.length==0){
            paint.setStyle(Paint.Style.FILL);
            int pointStride=Math.max(1,(points.length/3)/17500);
            for(int i=0;i<points.length;i+=pointStride*3){
                float[] a=project(points[i],points[i+1],points[i+2],orientation,cx,cy,focal);
                if(a==null||a[0]<0||a[0]>w||a[1]<0||a[1]>h)continue;
                float radius=Math.max(1.1f,Math.min(3.6f,3.0f/(float)Math.sqrt(a[2])));
                int alpha=(int)Math.max(50,Math.min(230,247-a[2]*15));
                paint.setColor(Color.argb(alpha,105,216,252));
                canvas.drawCircle(a[0],a[1],radius,paint);dots++;
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x929eeefc);
        paint.setStrokeWidth(2.3f);
        canvas.drawLine(cx-13,cy,cx-6,cy,paint);
        canvas.drawLine(cx+6,cy,cx+13,cy,paint);
        canvas.drawLine(cx,cy-13,cx,cy-6,paint);
        canvas.drawLine(cx,cy+6,cx,cy+13,paint);
        paint.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);
        paint.setColor(0xff9deaf3);
        canvas.drawText(edges.length>0
            ?("REAL DEPTH LINES "+drawn+" · POINTS "+dots)
            :("NO DEPTH SURFACES · FEATURE DOTS "+dots),dp(13),h*.68f,paint);
        paint.setColor(0xffffc495);
        String label=walking
            ?String.format(Locale.US,"STEP ESTIMATE %.1f m · ERROR ±%.1f m",travel,uncertainty())
            :(steps>0?String.format(Locale.US,"STEPS PAUSED · %.1f m · DRIFT UNKNOWN",travel):
            "SAVED VIEWPOINT · NO LIVE WALK TRACKING");
        canvas.drawText(label,dp(13),h*.73f,paint);
        if(stepCap)canvas.drawText("STEP LIMIT REACHED — RESET BEFORE MORE ESTIMATES",
            dp(13),h*.76f,paint);
        drawOverhead(canvas,orientation);
    }
    private void drawOverhead(Canvas canvas,float[] cameraRotation){
        if(points.length<30)return;
        float x0=Float.MAX_VALUE,x1=-Float.MAX_VALUE,z0=Float.MAX_VALUE,z1=-Float.MAX_VALUE;
        for(int i=0;i<points.length;i+=12){
            float x=points[i],z=points[i+2];
            if(x<x0)x0=x;if(x>x1)x1=x;
            if(z<z0)z0=z;if(z>z1)z1=z;
        }
        float span=Math.max(1.75f,Math.max(x1-x0,z1-z0));
        // This is an occupancy *sketch* of measured points, not a certified floor plan.
        float box=dp(127),left=getWidth()-box-dp(12),top=dp(211);
        float inside=box-dp(17),scale=inside/span;
        float xCenter=(x0+x1)*.5f,zCenter=(z0+z1)*.5f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xee071f2c);
        canvas.drawRoundRect(left,top,left+box,top+box,dp(10),dp(10),paint);
        paint.setColor(0xff92dfea);
        paint.setTextSize(dp(9));
        canvas.drawText("SCANNED TOP VIEW",left+dp(8),top+dp(13),paint);
        canvas.save();
        canvas.clipRect(left+dp(4),top+dp(18),left+box-dp(4),top+box-dp(4));
        for(int i=0;i<points.length;i+=Math.max(3,((points.length/3)/1100)*3)){
            float px=left+box*.5f+(points[i]-xCenter)*scale;
            float py=top+box*.52f+(points[i+2]-zCenter)*scale;
            paint.setColor(0x8b5acbdf);
            canvas.drawCircle(px,py,dp(.75f),paint);
        }
        if(edges.length>0){
            paint.setStrokeWidth(dp(.8f));paint.setColor(0xbb4ff5e9);
            for(int i=0;i<edges.length;i+=Math.max(6,((edges.length/6)/700)*6)){
                float px=left+box*.5f+(edges[i]-xCenter)*scale;
                float py=top+box*.52f+(edges[i+2]-zCenter)*scale;
                float qx=left+box*.5f+(edges[i+3]-xCenter)*scale;
                float qy=top+box*.52f+(edges[i+5]-zCenter)*scale;
                canvas.drawLine(px,py,qx,qy,paint);
            }
        }
        float px=left+box*.5f+(cameraLocation[0]-xCenter)*scale;
        float py=top+box*.52f+(cameraLocation[2]-zCenter)*scale;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xfffbc389);canvas.drawCircle(px,py,dp(3),paint);
        paint.setStrokeWidth(dp(1.8f));
        paint.setColor(0xfffbdcb6);
        float dx=-cameraRotation[2],dz=-cameraRotation[8];
        float d=(float)Math.hypot(dx,dz);
        if(d>.01f)canvas.drawLine(px,py,px+dx/d*dp(14),py+dz/d*dp(14),paint);
        if(steps>0){
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0x99ffc38f);paint.setStrokeWidth(dp(.8f));
            canvas.drawCircle(px,py,uncertainty()*scale,paint);
        }
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
