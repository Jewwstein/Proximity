package com.echosight.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import java.util.Locale;

/**
 * EchoSight 0.3: lit ARCore scan and orientation-only dark spatial memory.
 *
 * Critical: darkness means no live position localization. The saved map is
 * a visualization from a FROZEN viewpoint. Never use for walking/navigation.
 */
public final class MainActivity extends Activity {
    private static final int CAMERA_REQUEST=41,STEP_REQUEST=42;
    private static final int CYAN=0xff7de8f8,WHITE=0xffe9f8ff,INK=0xff03111c;
    private GLSurfaceView surface;
    private ScanRenderer renderer;
    private MemoryView memoryView;
    private SpatialMap map;
    private PhotoAtlas photoAtlas;
    private ImageView photoDisplay;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService decodeWorker=Executors.newSingleThreadExecutor();
    private Bitmap displayedPhoto;
    private int photoSerial=0,selectedPhoto=-1;
    private long lastPhotoChange=0;
    private TextView status,detail,warning,title;
    private Button scanButton,saveButton,darkButton,deleteButton,walkButton,wideButton,styleButton,strideButton;
    private Session session;
    private boolean installRequested=false,scanning=false,wideFrame=true;
    private final float[] strideChoices={.50f,.63f,.76f,.90f};
    private int strideChoice=1,displayStyle=0;
    private float lastTouchX,lastTouchY;
    private int frameMessages=0;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        map=new SpatialMap(this);
        map.load();
        photoAtlas=new PhotoAtlas(this);
        createUI();
        showMemoryIfPresent();
    }
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private GradientDrawable rounded(int bg){
        GradientDrawable d=new GradientDrawable();d.setColor(bg);d.setCornerRadius(dp(12));return d;
    }
    private TextView label(String string,int size,int color,boolean heavy){
        TextView v=new TextView(this);v.setText(string);
        v.setTextColor(color);v.setTextSize(size);v.setGravity(Gravity.CENTER_VERTICAL);
        if(heavy)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private Button button(String text){
        Button b=new Button(this);b.setAllCaps(false);b.setText(text);
        b.setTextColor(CYAN);b.setTextSize(13);b.setBackground(rounded(0xf5204255));
        return b;
    }
    private void createUI(){
        FrameLayout root=new FrameLayout(this);
        root.setBackgroundColor(INK);setContentView(root);
        surface=new GLSurfaceView(this);
        surface.setEGLContextClientVersion(2);
        renderer=new ScanRenderer(map,photoAtlas,(count,frames,tracking,message)->runOnUiThread(()->{
            if(!scanning)return;
            if(frameMessages++%2==0){
                status.setText(tracking?"SCANNING · "+photoAtlas.count()+" ROOM PHOTOS":"TRACKING UNAVAILABLE");
                detail.setText(message+(tracking?" · move slowly through the lit room":" · add light or texture"));
            }
        }));
        surface.setRenderer(renderer);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        surface.setVisibility(View.GONE);

        photoDisplay=new ImageView(this);
        photoDisplay.setBackgroundColor(INK);
        photoDisplay.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(photoDisplay,new FrameLayout.LayoutParams(-1,-1));
        photoDisplay.setVisibility(View.GONE);

        memoryView=new MemoryView(this);
        memoryView.setViewUpdates((position,forward,steps,error)->
            showRecordedView(position,forward,steps,error));
        memoryView.setStepUpdates((count,distance,error)->runOnUiThread(()->{
            if(walkButton==null)return;
            if(count>=24){
                walkButton.setText("STEP LIMIT");
                warning.setText("DRIFT LIMIT REACHED · STOP · RESET AT KNOWN LOCATION");
            }else{
                walkButton.setText("PAUSE WALK");
                warning.setText(String.format(Locale.US,
                    "STEP ESTIMATE %d · %.1f m · ±%.1f m drift · NOT SAFE",count,distance,error));
            }
        }));
        root.addView(memoryView,new FrameLayout.LayoutParams(-1,-1));
        memoryView.setOnTouchListener((view,e)->{
            if(!view.isShown())return false;
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                lastTouchX=e.getX();lastTouchY=e.getY();return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                float dx=e.getX()-lastTouchX,dy=e.getY()-lastTouchY;
                lastTouchX=e.getX();lastTouchY=e.getY();
                memoryView.turnVirtual(-dx,dy);
                return true;
            }
            return e.getActionMasked()==MotionEvent.ACTION_UP;
        });

        LinearLayout top=new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(15),dp(28),dp(15),dp(14));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xf503111c,0xdf03111c,0x0003111c}));
        root.addView(top,new FrameLayout.LayoutParams(-1,dp(197),Gravity.TOP));
        title=label("ECHOSIGHT / SPATIAL MEMORY",20,CYAN,true);
        top.addView(title,new LinearLayout.LayoutParams(-1,dp(38)));
        status=label("SAVED MAP • "+map.count()+" points",15,WHITE,true);
        top.addView(status,new LinearLayout.LayoutParams(-1,dp(33)));
        detail=label("Scan your room in light, then review the stored 3D dots in the dark.",12,WHITE,false);
        detail.setMaxLines(3);
        top.addView(detail,new LinearLayout.LayoutParams(-1,dp(57)));
        warning=label("EXPERIMENT · NO LIVE POSITION TRACKING IN DARKNESS",11,0xffffc1a4,true);
        warning.setMaxLines(2);
        top.addView(warning,new LinearLayout.LayoutParams(-1,dp(32)));

        LinearLayout controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(10),dp(16),dp(10),dp(21));
        controls.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xff03111c,0xee03111c,0x0003111c}));
        root.addView(controls,new FrameLayout.LayoutParams(-1,dp(247),Gravity.BOTTOM));
        LinearLayout first=new LinearLayout(this);
        first.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(first,new LinearLayout.LayoutParams(-1,dp(53)));
        scanButton=button("SCAN IN LIGHT");
        first.addView(scanButton,new LinearLayout.LayoutParams(0,dp(47),1));
        scanButton.setOnClickListener(v->startScan());
        saveButton=button("SAVE MAP");
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(47),.75f);sp.leftMargin=dp(7);
        first.addView(saveButton,sp);
        saveButton.setOnClickListener(v->saveMap());
        LinearLayout second=new LinearLayout(this);
        second.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(second,new LinearLayout.LayoutParams(-1,dp(53)));
        darkButton=button("DARK MEMORY");
        second.addView(darkButton,new LinearLayout.LayoutParams(0,dp(47),1));
        darkButton.setOnClickListener(v->openMemory());
        walkButton=button("TRY STEPS");
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,dp(47),.8f);wp.leftMargin=dp(7);
        second.addView(walkButton,wp);
        walkButton.setOnClickListener(v->toggleWalking());
        LinearLayout third=new LinearLayout(this);
        third.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(third,new LinearLayout.LayoutParams(-1,dp(53)));
        wideButton=button("WIDE CAM");
        third.addView(wideButton,new LinearLayout.LayoutParams(0,dp(45),1));
        wideButton.setOnClickListener(v->{
            wideFrame=!wideFrame;
            renderer.setWideView(wideFrame);
            wideButton.setText(wideFrame?"WIDE CAM":"FULL CAM");
        });
        styleButton=button("SURFACES");
        LinearLayout.LayoutParams sy=new LinearLayout.LayoutParams(0,dp(45),1);
        sy.leftMargin=dp(5);
        third.addView(styleButton,sy);
        styleButton.setOnClickListener(v->{
            displayStyle=(displayStyle+1)%3;memoryView.setStyle(displayStyle);
            styleButton.setText(displayStyle==0?"SURFACES":displayStyle==1?"POINTS":"BOTH");
        });
        strideButton=button("STEP .63m");
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(0,dp(45),1);
        sl.leftMargin=dp(5);third.addView(strideButton,sl);
        strideButton.setOnClickListener(v->{
            strideChoice=(strideChoice+1)%strideChoices.length;
            memoryView.setStepLength(strideChoices[strideChoice]);
            strideButton.setText(String.format(Locale.US,"STEP %.2fm",strideChoices[strideChoice]));
        });
        deleteButton=button("CLEAR");
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(45),.65f);cp.leftMargin=dp(5);
        third.addView(deleteButton,cp);
        deleteButton.setOnClickListener(v->{
            if(scanning)stopScan();
            map.delete();photoAtlas.reset();
            memoryView.setPhotoMode(false);photoDisplay.setVisibility(View.GONE);
            memoryView.deactivate();memoryView.setVisibility(View.VISIBLE);memoryView.activate(map);
            status.setText("MAP CLEARED");
            detail.setText("Scan a lit room to record actual 3D points.");
            walkButton.setText("TRY STEPS");
        });
        TextView caution=label(
            "STEP MODE estimates movement; drift grows with each step. "+
            "NOT safe for walking in darkness, stairs or avoiding obstacles.",
            11,0xffffc0a7,false);
        caution.setGravity(Gravity.CENTER);caution.setMaxLines(3);
        controls.addView(caution,new LinearLayout.LayoutParams(-1,dp(42)));
    }
    private void showMemoryIfPresent(){
        surface.setVisibility(View.GONE);
        memoryView.setVisibility(View.VISIBLE);
        if(map.count()>0 || photoAtlas.hasPhotos()){
            photoDisplay.setVisibility(photoAtlas.hasPhotos()?View.VISIBLE:View.GONE);
            memoryView.setPhotoMode(photoAtlas.hasPhotos());
            memoryView.activate(map);
            memoryView.updatePhoto();
            status.setText(photoAtlas.count()+" recorded photos · "+map.count()+" points");
            detail.setText("Actual recorded room views. Turning chooses matching frames; walking position is only estimated.");
        }else{
            memoryView.setPhotoMode(false);
            photoDisplay.setVisibility(View.GONE);
            memoryView.activate(map);
            status.setText("NO 3D MAP YET");
            detail.setText("Tap SCAN IN LIGHT, move slowly through your room, then SAVE MAP.");
        }
    }
    private boolean cameraAllowed(){
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_REQUEST);
            return false;
        }
        return true;
    }
    private void startScan(){
        if(!cameraAllowed())return;
        try{
            ArCoreApk.Availability availability=ArCoreApk.getInstance().checkAvailability(this);
            if(availability.isUnsupported()){
                status.setText("ARCORE NOT SUPPORTED");
                detail.setText("This phone cannot run Google ARCore room scanning. Saved viewer still works.");
                return;
            }
            ArCoreApk.InstallStatus installed=ArCoreApk.getInstance()
                .requestInstall(this,!installRequested);
            if(installed==ArCoreApk.InstallStatus.INSTALL_REQUESTED){
                installRequested=true;
                status.setText("INSTALL GOOGLE PLAY SERVICES FOR AR");
                detail.setText("Install or update AR service, then return to EchoSight.");
                return;
            }
            if(session==null){
                // Start a new photographic atlas in this AR coordinate space.
                // Old photos and old geometry cannot share a new AR origin.
                photoAtlas.reset();
                // A new ARCore session has a new coordinate origin. Never merge
                // an old saved map into newly acquired world coordinates.
                if(map.count()>0){
                    map.clear(); // Existing saved file remains until SAVE MAP overwrites it.
                    status.setText("NEW SCAN · previous file kept until saving");
                }
                session=new Session(this);
                Config config=session.getConfig();
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
                boolean depth=session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
                if(depth)config.setDepthMode(Config.DepthMode.AUTOMATIC);
                session.configure(config);
                renderer.setSession(session,depth);
                detail.setText(depth?"ARCore Depth API available":"ARCore tracking available; Depth API not available");
            }
            if(scanning)return;
            memoryView.deactivate();memoryView.setVisibility(View.GONE);
            photoDisplay.setVisibility(View.GONE);
            surface.setVisibility(View.VISIBLE);
            session.resume();
            surface.onResume();
            renderer.setRotation(getWindowManager().getDefaultDisplay().getRotation());
            renderer.setRecording(true);
            scanning=true;
            scanButton.setText("SCANNING…");
            status.setText("SCANNING LIT ROOM");
            detail.setText("Sweep slowly across walls, floor, bed and furniture. Use good lighting.");
            warning.setText("LIVE CAMERA SCAN · SAVE WHEN FINISHED");
        }catch(Throwable error){
            status.setText("ARCORE SCAN CANNOT START");
            detail.setText(error.getClass().getSimpleName()+": "+error.getMessage());
        }
    }
    private void stopScan(){
        if(!scanning)return;
        scanning=false;
        renderer.setRecording(false);
        try{surface.onPause();}catch(Throwable ignored){}
        try{if(session!=null)session.pause();}catch(Throwable ignored){}
        surface.setVisibility(View.GONE);
        scanButton.setText("SCAN IN LIGHT");
    }
    private void saveMap(){
        if(photoAtlas.count()<3 && map.count()<35){
            status.setText("NOT ENOUGH RECORDED IMAGES");
            detail.setText("Scan a well-lit textured space and collect more points first.");
            return;
        }
        boolean saved=map.save();
        photoAtlas.save();
        status.setText(saved?"3D ROOM MEMORY SAVED":"COULD NOT SAVE MAP");
        detail.setText(saved?(photoAtlas.count()+" actual photos recorded, plus "+map.surfaceCount()+" optional depth lines. Open DARK MEMORY.") :
            "Map unavailable or storage error.");
    }
    private void openMemory(){
        if(scanning)stopScan();
        if(map.count()==0 && map.exists())map.load();
        photoAtlas.load();
        boolean photos=photoAtlas.hasPhotos();
        selectedPhoto=-1;photoSerial++;
        photoDisplay.setVisibility(photos?View.VISIBLE:View.GONE);
        memoryView.setPhotoMode(photos);
        memoryView.setVisibility(View.VISIBLE);
        memoryView.activate(map);
        memoryView.updatePhoto();
        warning.setText("POSITION FROZEN · DO NOT WALK USING THIS MAP");
        status.setText(photoAtlas.count()+" RECORDED VIEWS · "+map.count()+" SPATIAL FEATURES");
        detail.setText("Showing saved photos, NOT live video. Steps select approximate viewpoints; no free 3D movement.");
        walkButton.setText("TRY STEPS");
        if(map.count()<35 && photoAtlas.count()==0)status.setText("NO SAVED PHOTOS — SCAN FIRST");
    }
    private void showRecordedView(float[] position,float[] forward,int steps,float uncertainty){
        if(photoDisplay==null||photoDisplay.getVisibility()!=View.VISIBLE)return;
        // This chooses the closest PRE-RECORDED image, not a synthetic
        // viewpoint. A mismatch displays no photo rather than a wrong hallway.
        PhotoAtlas.Choice nearest=photoAtlas.choose(position,forward);
        if(nearest==null){
            selectedPhoto=-1;
            photoSerial++;
            photoDisplay.setImageDrawable(null);
            detail.setText("No saved photo matches this estimated angle/location. Not a live view.");
            return;
        }
        int selection=nearest.entry.number;
        if(selection==selectedPhoto)return;
        long now=System.currentTimeMillis();
        if(now-lastPhotoChange<280)return;
        selectedPhoto=selection;
        lastPhotoChange=now;
        int serial=++photoSerial;
        decodeWorker.execute(()->{
            Bitmap image=photoAtlas.image(nearest.entry);
            ui.post(()->{
                if(serial!=photoSerial || photoDisplay.getVisibility()!=View.VISIBLE){
                    if(image!=null)image.recycle();
                    return;
                }
                if(image==null){
                    selectedPhoto=-1;
                    return;
                }
                Bitmap old=displayedPhoto;
                displayedPhoto=image;
                photoDisplay.setImageBitmap(image);
                if(old!=null&&!old.isRecycled())ui.postDelayed(()->{
                    if(!old.isRecycled())old.recycle();
                },400);
                detail.setText(String.format(Locale.US,
                    "Recorded frame %d/%d | match %.1f m / %.0f deg | NOT LIVE",
                    nearest.entry.number+1,photoAtlas.count(),nearest.distance,
                    Math.toDegrees(nearest.angle)));
            });
        });
    }
    private void toggleWalking(){
        if(scanning){
            status.setText("SAVE MAP FIRST");
            detail.setText("Step estimates only run from a saved mapped start point.");
            return;
        }
        if(map.count()<35 && photoAtlas.count()==0){status.setText("NO MAP FOR STEP TEST");return;}
        if(memoryView.isWalking()){
            memoryView.setWalking(false);
            walkButton.setText("RESUME STEPS");
            warning.setText("STEP ESTIMATE PAUSED · position still uncertain");
            return;
        }
        if(!memoryView.hasStepSensor()){
            status.setText("NO ANDROID STEP SENSOR FOUND");
            detail.setText("This phone did not expose TYPE_STEP_DETECTOR or TYPE_STEP_COUNTER.");
            return;
        }
        if(android.os.Build.VERSION.SDK_INT>=29 &&
           ContextCompat.checkSelfPermission(this,Manifest.permission.ACTIVITY_RECOGNITION)
               !=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION},STEP_REQUEST);
            return;
        }
        beginExperimentalWalking();
    }
    private void beginExperimentalWalking(){
        boolean okay=memoryView.setWalking(true);
        if(okay){
            walkButton.setText("PAUSE WALK");
            warning.setText("STEP MODE EXPERIMENT · FACE PHONE IN TRAVEL DIRECTION · DRIFT");
            detail.setText("Assuming start at LAST SCANNED camera location. Steps and heading are only estimates.");
        }else{
            status.setText("STEP SENSOR NOT AVAILABLE");
            detail.setText("Could not activate Android step counter/detector.");
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] result){
        super.onRequestPermissionsResult(request,permissions,result);
        if(request==CAMERA_REQUEST && result.length>0 &&result[0]==PackageManager.PERMISSION_GRANTED)
            startScan();
        if(request==STEP_REQUEST && result.length>0 &&result[0]==PackageManager.PERMISSION_GRANTED)
            beginExperimentalWalking();
    }
    @Override protected void onResume(){
        super.onResume();
        if(scanning&&session!=null){
            try{session.resume();surface.onResume();}
            catch(Throwable e){status.setText("ARCore resume failed: "+e.getMessage());}
        }else if(memoryView!=null&&memoryView.getVisibility()==View.VISIBLE){
            memoryView.activate(map);
        }
    }
    @Override protected void onPause(){
        if(scanning){
            renderer.setRecording(false);
            try{surface.onPause();}catch(Throwable ignored){}
            try{if(session!=null)session.pause();}catch(Throwable ignored){}
            scanning=false;scanButton.setText("SCAN IN LIGHT");
            if(map.count()>=35 || photoAtlas.count()>=3)map.save();
            photoAtlas.save();
            if(memoryView!=null){memoryView.setVisibility(View.VISIBLE);memoryView.activate(map);}
        }
        if(memoryView!=null)memoryView.deactivate();
        super.onPause();
    }
    @Override protected void onDestroy(){
        stopScan();
        decodeWorker.shutdownNow();
        if(displayedPhoto!=null&&!displayedPhoto.isRecycled())displayedPhoto.recycle();
        if(session!=null)try{session.close();}catch(Throwable ignored){}
        if(memoryView!=null)memoryView.deactivate();
        super.onDestroy();
    }
}
