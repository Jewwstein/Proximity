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

/**
 * Renders STORED ARCore points relative to a frozen physical location.
 * Phone rotation updates direction only; accelerometer motion is NOT used
 * to fabricate a physical position. Never use this as a walking guide.
 */
public final class MemoryView extends View implements SensorEventListener {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SensorManager sensors;
    private final Sensor orientationSensor;
    private float[] points=new float[0];
    private float[] cameraLocation=new float[]{0,0,0};
    private float[] cameraQuaternion=new float[]{0,0,0,1};
    private final float[] sensorNow=new float[9];
    private final float[] sensorStart=new float[9];
    private boolean startKnown=false,sensorKnown=false,enabled=false;
    private int visible=0;
    private float manualYaw=0,manualPitch=0;
    private long lastRender=0;
    public MemoryView(Context c){
        super(c);
        sensors=(SensorManager)c.getSystemService(Context.SENSOR_SERVICE);
        Sensor game=sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        orientationSensor=game!=null?game:
            sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
    }
    private static void identity(float[] r){Arrays.fill(r,0);r[0]=r[4]=r[8]=1f;}
    public void activate(SpatialMap map){
        points=map.snapshot();
        cameraLocation=map.position();
        cameraQuaternion=map.orientation();
        startKnown=false;manualYaw=0;manualPitch=0;
        enabled=true;
        if(sensors!=null&&orientationSensor!=null)
            sensors.registerListener(this,orientationSensor,SensorManager.SENSOR_DELAY_GAME);
        invalidate();
    }
    public void deactivate(){
        enabled=false;
        if(sensors!=null)sensors.unregisterListener(this);
        invalidate();
    }
    public int numberOfPoints(){return points.length/3;}
    public boolean hasDirectionSensor(){return orientationSensor!=null;}
    @Override public void onSensorChanged(SensorEvent e){
        if(!enabled||e.sensor!=orientationSensor)return;
        synchronized(sensorNow){
            SensorManager.getRotationMatrixFromVector(sensorNow,e.values);
            if(!startKnown) {
                System.arraycopy(sensorNow,0,sensorStart,0,9);
                startKnown=true;
            }
            sensorKnown=true;
        }
        if(System.currentTimeMillis()-lastRender>=52){
            lastRender=System.currentTimeMillis();
            invalidate();
        }
    }
    @Override public void onAccuracyChanged(Sensor s,int accuracy){}
    public void turnVirtual(float dx,float dy){
        manualYaw+=dx*.004f;
        manualPitch=Math.max(-.75f,Math.min(.75f,manualPitch+dy*.004f));
        invalidate();
    }
    private static void multiply(float[] a,float[] b,float[] out){
        for(int row=0;row<3;row++){
            for(int col=0;col<3;col++){
                float sum=0;
                for(int k=0;k<3;k++)sum+=a[row*3+k]*b[k*3+col];
                out[row*3+col]=sum;
            }
        }
    }
    private static void transpose(float[] in,float[] out){
        for(int r=0;r<3;r++)for(int c=0;c<3;c++)out[r*3+c]=in[c*3+r];
    }
    private static float[] quaternionMatrix(float[] q){
        float x=q[0],y=q[1],z=q[2],w=q[3];
        float[] r=new float[9];
        r[0]=1-2*(y*y+z*z);
        r[1]=2*(x*y-z*w);
        r[2]=2*(x*z+y*w);
        r[3]=2*(x*y+z*w);
        r[4]=1-2*(x*x+z*z);
        r[5]=2*(y*z-x*w);
        r[6]=2*(x*z-y*w);
        r[7]=2*(y*z+x*w);
        r[8]=1-2*(x*x+y*y);
        return r;
    }
    private float[] worldCameraRotation(){
        float[] base=quaternionMatrix(cameraQuaternion);
        float[] delta=new float[9];identity(delta);
        if(sensorKnown&&startKnown){
            float[] now=new float[9],start=new float[9],startT=new float[9];
            synchronized(sensorNow){
                System.arraycopy(sensorNow,0,now,0,9);
                System.arraycopy(sensorStart,0,start,0,9);
            }
            transpose(start,startT);
            multiply(startT,now,delta);
        }
        if(Math.abs(manualYaw)+Math.abs(manualPitch)>.0001f){
            float cy=(float)Math.cos(manualYaw),sy=(float)Math.sin(manualYaw);
            float cp=(float)Math.cos(manualPitch),sp=(float)Math.sin(manualPitch);
            float[] yaw={cy,0,sy, 0,1,0, -sy,0,cy};
            float[] pitch={1,0,0, 0,cp,-sp, 0,sp,cp};
            float[] tmp=new float[9];multiply(yaw,pitch,tmp);
            float[] combined=new float[9];multiply(delta,tmp,combined);delta=combined;
        }
        float[] world=new float[9];multiply(base,delta,world);
        return world;
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        canvas.drawColor(0xff020b14);
        if(!enabled)return;
        float[] world=worldCameraRotation();
        int width=getWidth(),height=getHeight();
        float cx=width*.5f,cy=height*.50f,focal=width*.95f;
        int count=0;
        // Maximum of ~24k points per view; low resolution memory makes
        // movement transparent and phone responsive.
        int stride=Math.max(1,(points.length/3)/24000);
        for(int i=0;i<points.length;i+=stride*3){
            float dx=points[i]-cameraLocation[0];
            float dy=points[i+1]-cameraLocation[1];
            float dz=points[i+2]-cameraLocation[2];
            float x=world[0]*dx+world[3]*dy+world[6]*dz;
            float y=world[1]*dx+world[4]*dy+world[7]*dz;
            float z=world[2]*dx+world[5]*dy+world[8]*dz;
            float distance=-z;
            if(distance<.28f||distance>16f)continue;
            float px=cx+focal*x/distance,py=cy-focal*y/distance;
            if(px<0||px>width||py<0||py>height)continue;
            float radius=Math.max(.75f,Math.min(3.6f,3.2f/(float)Math.sqrt(distance)));
            int alpha=(int)Math.max(43,Math.min(245,245-distance*14));
            paint.setColor(Color.argb(alpha,96,229,255));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(px,py,radius,paint);
            count++;
        }
        visible=count;
        paint.setColor(0x8076deeb);paint.setStrokeWidth(2f);
        canvas.drawLine(cx-12,cy,cx-5,cy,paint);
        canvas.drawLine(cx+5,cy,cx+12,cy,paint);
        canvas.drawLine(cx,cy-12,cx,cy-5,paint);
        canvas.drawLine(cx,cy+5,cx,cy+12,paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(Math.max(12,getResources().getDisplayMetrics().scaledDensity*12));
        paint.setColor(0xff9be5ef);
        canvas.drawText("SAVED GEOMETRY • "+count+" points in view",18,height*.70f,paint);
        paint.setColor(0xfff0bd8e);
        canvas.drawText("POSITION FROZEN • NOT LIVE NAVIGATION",18,height*.75f,paint);
    }
}
