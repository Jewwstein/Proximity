package com.echosight.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import android.graphics.Bitmap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EchoSight: actual low-light camera photons + exploratory one-mic echogram.
 *
 * No real 3D geometry is claimed; a single channel of acoustic reflection does
 * not contain reliable azimuth. DO NOT USE FOR NAVIGATION OR COLLISION AVOIDANCE.
 */
public final class MainActivity extends ComponentActivity {
    private static final int CAMERA_REQUEST=27, AUDIO_REQUEST=28;
    private static final int CYAN=0xff78e9f9, TEXT=0xffedfaff, DEEP=0xff04101b;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService videoWorker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean videoBusy=new AtomicBoolean(false);
    private final EchoAudio sonar=new EchoAudio();
    private PreviewView preview;
    private ImageView enhancedImage;
    private TextView cameraState,sonarState,mainMessage;
    private Button soundToggle,modeToggle,torchToggle;
    private SonarOverlay graphic;
    private Camera boundCamera;
    private android.graphics.Bitmap latestRendered;
    private int mode=VisualPipeline.FUSION;
    private float gain=1.20f;
    private boolean torchOn=false,active=true;
    private boolean requestSoundAfterPermission=false;
    private int previousDarkCounter=0;
    private final Runnable cameraLoop=new Runnable() {
        @Override public void run(){
            if(!active)return;
            captureAndEnhance();
            graphic.invalidate();
            ui.postDelayed(this,155);
        }
    };

    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private GradientDrawable rounded(int background,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(background);d.setCornerRadius(dp(radius));return d;
    }
    private TextView text(String label,int size,int color,boolean bold){
        TextView v=new TextView(this);
        v.setText(label);v.setTextColor(color);v.setTextSize(size);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private Button button(String label){
        Button v=new Button(this);v.setAllCaps(false);v.setText(label);
        v.setTextSize(14);v.setTextColor(CYAN);
        v.setBackground(rounded(0xe9213647,14));
        return v;
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(DEEP);
        getWindow().setNavigationBarColor(DEEP);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
            View.SYSTEM_UI_FLAG_FULLSCREEN);
        assembleScreen();
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)
            !=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_REQUEST);
        else openCamera();
        ui.postDelayed(cameraLoop,350);
    }

    private void assembleScreen(){
        FrameLayout root=new FrameLayout(this);
        root.setBackgroundColor(DEEP);setContentView(root);
        preview=new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(preview,new FrameLayout.LayoutParams(-1,-1));

        enhancedImage=new ImageView(this);
        enhancedImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        enhancedImage.setBackgroundColor(DEEP);
        root.addView(enhancedImage,new FrameLayout.LayoutParams(-1,-1));

        graphic=new SonarOverlay();
        root.addView(graphic,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20),dp(32),dp(16),dp(10));
        header.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xf604101b,0xd604101b,0x8004101b,0x0004101b}));
        root.addView(header,new FrameLayout.LayoutParams(-1,dp(180),Gravity.TOP));
        TextView brand=text("E C H O S I G H T",22,CYAN,true);
        header.addView(brand,new LinearLayout.LayoutParams(-1,dp(40)));
        mainMessage=text("A visual sonar experiment · camera contours + sound echoes",13,TEXT,false);
        mainMessage.setMaxLines(2);
        header.addView(mainMessage,new LinearLayout.LayoutParams(-1,dp(48)));
        cameraState=text("CAMERA · starting",12,0xffc8e9ee,false);
        header.addView(cameraState,new LinearLayout.LayoutParams(-1,dp(24)));
        sonarState=text("SONAR · off (enable below)",12,0xff9dd5e0,false);
        sonarState.setMaxLines(2);
        header.addView(sonarState,new LinearLayout.LayoutParams(-1,dp(38)));

        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(12),dp(18),dp(12),dp(19));
        controls.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xff04101b,0xff04101b,0xed04101b,0x0004101b}));
        root.addView(controls,new FrameLayout.LayoutParams(-1,dp(211),Gravity.BOTTOM));

        LinearLayout buttons=new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(buttons,new LinearLayout.LayoutParams(-1,dp(56)));
        soundToggle=button("START SONAR");
        buttons.addView(soundToggle,new LinearLayout.LayoutParams(0,dp(50),1.2f));
        soundToggle.setOnClickListener(v->toggleSonar());
        modeToggle=button("FUSION");
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(50),1);
        mp.leftMargin=dp(7);buttons.addView(modeToggle,mp);
        modeToggle.setOnClickListener(v->cycleMode());
        torchToggle=button("LAMP OFF");
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(50),1);
        lp.leftMargin=dp(7);buttons.addView(torchToggle,lp);
        torchToggle.setOnClickListener(v->toggleTorch());

        LinearLayout gainRow=new LinearLayout(this);
        gainRow.setGravity(Gravity.CENTER_VERTICAL);
        gainRow.addView(text("GLOW",12,CYAN,true),
            new LinearLayout.LayoutParams(dp(53),dp(39)));
        SeekBar brightness=new SeekBar(this);
        brightness.setMax(100);brightness.setProgress(28);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(45),1);
        gainRow.addView(brightness,sp);
        brightness.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
        brightness.setThumbTintList(android.content.res.ColorStateList.valueOf(CYAN));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar v,int value,boolean fromUser){
                gain=.8f+2.15f*(value/100f);
            }
            @Override public void onStartTrackingTouch(SeekBar v){}
            @Override public void onStopTrackingTouch(SeekBar v){}
        });
        controls.addView(gainRow,new LinearLayout.LayoutParams(-1,dp(48)));

        TextView footnote=text("EXPLORE ONLY · NOT SAFE FOR WALKING OR OBSTACLE AVOIDANCE\n"+
            "Audio estimates range, NOT direction. No map without reliable echoes.",11,0xffd1b9b1,false);
        footnote.setGravity(Gravity.CENTER);
        controls.addView(footnote,new LinearLayout.LayoutParams(-1,dp(56)));
    }

    private void openCamera(){
        ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{
            try{
                ProcessCameraProvider provider=future.get();
                provider.unbindAll();
                Preview video=new Preview.Builder().build();
                video.setSurfaceProvider(preview.getSurfaceProvider());
                boundCamera=provider.bindToLifecycle(this,
                    CameraSelector.DEFAULT_BACK_CAMERA,video);
                try {
                    Range<Integer> limits=boundCamera.getCameraInfo()
                        .getExposureState().getExposureCompensationRange();
                    int positive=Math.max(0,limits.getUpper());
                    if(positive>0)boundCamera.getCameraControl()
                        .setExposureCompensationIndex(Math.max(1,positive/2));
                }catch(Exception ignored){}
                cameraState.setText("CAMERA · live · enhancing available light");
            }catch(Exception e){
                cameraState.setText("CAMERA FAILED · "+e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==CAMERA_REQUEST){
            if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)openCamera();
            else cameraState.setText("Camera permission required for image contours");
        }
        if(request==AUDIO_REQUEST){
            if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED &&
                requestSoundAfterPermission){
                requestSoundAfterPermission=false;
                startSonar();
            }else{
                requestSoundAfterPermission=false;
                sonarState.setText("SONAR · permission denied; camera mode still works");
            }
        }
    }

    private void captureAndEnhance(){
        if(videoBusy.get())return;
        Bitmap snapshot=preview.getBitmap();
        if(snapshot==null||snapshot.getWidth()<100)return;
        if(!videoBusy.compareAndSet(false,true)){snapshot.recycle();return;}
        final int videoMode=mode;final float chosenGain=gain;
        videoWorker.execute(()->{
            try{
                VisualPipeline.Frame result=VisualPipeline.render(snapshot,videoMode,chosenGain);
                ui.post(()->{
                    if(!active){result.bitmap.recycle();return;}
                    enhancedImage.setImageBitmap(result.bitmap);
                    latestRendered=result.bitmap;
                    if(result.insufficientLight) {
                        if(++previousDarkCounter>=3){
                            cameraState.setText("CAMERA · no usable light · sonar-only contours unavailable");
                        }
                        graphic.setCameraDark(true);
                    }else{
                        previousDarkCounter=0;
                        cameraState.setText("CAMERA · light boosted · "+
                            result.edgePixels+" real contours");
                        graphic.setCameraDark(false);
                    }
                });
            }catch(Throwable e){
                ui.post(()->cameraState.setText("Camera enhancement error: "+e.getMessage()));
            }finally{
                snapshot.recycle();
                videoBusy.set(false);
            }
        });
    }

    private void toggleSonar(){
        if(sonar.isRunning()){
            sonar.stop();
            graphic.setActive(false);
            sonarState.setText("SONAR · off");
            soundToggle.setText("START SONAR");
            return;
        }
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)
           !=PackageManager.PERMISSION_GRANTED){
            requestSoundAfterPermission=true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO_REQUEST);
            return;
        }
        startSonar();
    }
    private void startSonar(){
        sonarState.setText("SONAR · checking speaker/microphone reflections…");
        soundToggle.setText("STOP SONAR");
        graphic.setActive(true);
        sonar.start(reading->ui.post(()->{
            if(!active)return;
            graphic.setReading(reading);
            sonarState.setText("SONAR · "+reading.message);
            if(!sonar.isRunning()){
                soundToggle.setText("START SONAR");
                graphic.setActive(false);
            }
        }));
    }
    private void cycleMode(){
        mode=(mode+1)%3;
        modeToggle.setText(mode==VisualPipeline.FUSION?"FUSION":
            mode==VisualPipeline.EDGES?"OUTLINES":"ENHANCED");
        mainMessage.setText(mode==VisualPipeline.FUSION
            ?"Fusion · enhanced camera pixels + real cyan edges"
            :mode==VisualPipeline.EDGES
                ?"Outlines · actual camera edges; echo rings do not have direction"
                :"Enhanced · amplifying only light the camera receives");
    }
    private void toggleTorch(){
        if(boundCamera==null || !boundCamera.getCameraInfo().hasFlashUnit()){
            cameraState.setText("Torch unavailable on this camera");return;
        }
        torchOn=!torchOn;
        try{
            boundCamera.getCameraControl().enableTorch(torchOn);
            torchToggle.setText(torchOn?"LAMP ON":"LAMP OFF");
        }catch(Exception e){torchOn=false;torchToggle.setText("LAMP OFF");}
    }

    @Override protected void onResume(){
        super.onResume();
        active=true;
    }
    @Override protected void onPause(){
        active=false;
        sonar.stop();
        if(soundToggle!=null)soundToggle.setText("START SONAR");
        if(graphic!=null)graphic.setActive(false);
        super.onPause();
    }
    @Override protected void onDestroy(){
        active=false;
        sonar.stop();
        ui.removeCallbacks(cameraLoop);
        videoWorker.shutdownNow();
        if(boundCamera!=null && torchOn)boundCamera.getCameraControl().enableTorch(false);
        super.onDestroy();
    }

    private final class SonarOverlay extends View {
        private final Paint pen=new Paint(3);
        private float[] readings=new float[12];
        private float approximateRange=0,quality=0;
        private boolean sonarActive=false,cameraDark=false,hasEcho=false;
        SonarOverlay(){super(MainActivity.this);}
        void setActive(boolean active){sonarActive=active;invalidate();}
        void setCameraDark(boolean dark){cameraDark=dark;invalidate();}
        void setReading(EchoAudio.Reading r){
            readings=r.bands.clone();
            approximateRange=r.distanceMeters;quality=r.strength;
            hasEcho=r.calibrated && r.distanceMeters>0 && r.strength>=.47f;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            int w=getWidth(),h=getHeight();
            if(w==0||h==0)return;
            float cx=w*.5f,cy=h*.51f;
            if(sonarActive) {
                // Echo circles deliberately carry NO azimuth/bearing claim.
                pen.setStyle(Paint.Style.STROKE);
                for(int i=1;i<=4;i++){
                    float radius=dp(23)+i*dp(47);
                    int alpha=hasEcho?(i==Math.min(4,Math.max(1,(int)(approximateRange/.90f)))?140:35):25;
                    pen.setColor(Color.argb(alpha,88,230,250));
                    pen.setStrokeWidth(dp(i==3?2:1));
                    canvas.drawCircle(cx,cy,radius,pen);
                }
                pen.setStyle(Paint.Style.FILL);
                pen.setColor(0xffa9f3ff);pen.setTextSize(dp(12));
                pen.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));
                String caption=hasEcho
                    ?String.format(Locale.US,"POSSIBLE ECHO ~%.1f m · NO BEARING",approximateRange)
                    :"ECHO SCAN · RANGE ONLY";
                float px=pen.measureText(caption);
                canvas.drawText(caption,Math.max(dp(10),(w-px)/2f),
                    h-dp(240),pen);
                float barBase=h-dp(251),maxHeight=dp(43);
                for(int i=0;i<readings.length;i++){
                    float l=dp(14)+i*((w-dp(28))/12f);
                    float intensity=Math.min(1f,Math.max(0,readings[i]));
                    pen.setColor(Color.argb(80+(int)(175*intensity),65,219,237));
                    canvas.drawRoundRect(l,barBase-maxHeight*intensity,
                        l+Math.max(dp(3),(w-dp(28))/12f-dp(4)),
                        barBase,dp(2),dp(2),pen);
                }
                pen.setColor(0xffaddce4);pen.setTextSize(dp(10));
                pen.setTypeface(Typeface.DEFAULT);
                canvas.drawText("0.35 m",dp(15),barBase+dp(15),pen);
                canvas.drawText("~3.3 m",w-dp(59),barBase+dp(15),pen);
            }
            if(cameraDark) {
                pen.setColor(0xffa9d6e0);pen.setTypeface(Typeface.DEFAULT);
                pen.setTextAlign(Paint.Align.CENTER);
                pen.setTextSize(dp(14));
                canvas.drawText("NO CAMERA LIGHT · NO REAL VISUAL EDGES",
                    cx,h*.38f,pen);
                pen.setTextAlign(Paint.Align.LEFT);
            }
            // Fixed center reticle indicates phone direction, not sonar bearing.
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(dp(1.4f));
            pen.setColor(0x6580eeff);
            canvas.drawLine(cx-dp(12),cy,cx-dp(4),cy,pen);
            canvas.drawLine(cx+dp(4),cy,cx+dp(12),cy,pen);
            canvas.drawLine(cx,cy-dp(12),cx,cy-dp(4),pen);
            canvas.drawLine(cx,cy+dp(4),cx,cy+dp(12),pen);
            pen.setStyle(Paint.Style.FILL);
        }
    }
}
