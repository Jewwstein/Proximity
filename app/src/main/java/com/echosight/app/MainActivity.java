package com.echosight.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
    private static final int CAMERA_REQUEST=41;
    private static final int CYAN=0xff7de8f8,WHITE=0xffe9f8ff,INK=0xff03111c;
    private GLSurfaceView surface;
    private ScanRenderer renderer;
    private MemoryView memoryView;
    private SpatialMap map;
    private TextView status,detail,warning,title;
    private Button scanButton,saveButton,darkButton,deleteButton;
    private Session session;
    private boolean installRequested=false,scanning=false;
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
        renderer=new ScanRenderer(map,(count,frames,tracking,message)->runOnUiThread(()->{
            if(!scanning)return;
            if(frameMessages++%2==0){
                status.setText(tracking?"SCANNING · "+count+" 3D points":"TRACKING UNAVAILABLE");
                detail.setText(message+(tracking?" · move slowly through the lit room":" · add light or texture"));
            }
        }));
        surface.setRenderer(renderer);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        surface.setVisibility(View.GONE);

        memoryView=new MemoryView(this);
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
        root.addView(controls,new FrameLayout.LayoutParams(-1,dp(189),Gravity.BOTTOM));
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
        deleteButton=button("CLEAR MAP");
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(47),.75f);cp.leftMargin=dp(7);
        second.addView(deleteButton,cp);
        deleteButton.setOnClickListener(v->{
            if(scanning)stopScan();
            map.delete();
            memoryView.deactivate();memoryView.setVisibility(View.VISIBLE);memoryView.activate(map);
            status.setText("MAP CLEARED");
            detail.setText("Scan a lit room to record actual 3D points.");
        });
        TextView caution=label(
            "Saved view follows your TURNING only; walking is NOT tracked. "+
            "Do not use this app to navigate or avoid hazards in the dark.",
            11,0xffffc0a7,false);
        caution.setGravity(Gravity.CENTER);caution.setMaxLines(3);
        controls.addView(caution,new LinearLayout.LayoutParams(-1,dp(54)));
    }
    private void showMemoryIfPresent(){
        surface.setVisibility(View.GONE);
        memoryView.setVisibility(View.VISIBLE);
        if(map.count()>0){
            memoryView.activate(map);
            status.setText(map.count()+" stored 3D points");
            detail.setText("Saved perspective, NOT your current location. Turn to explore or swipe to rotate virtually.");
        }else{
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
                session=new Session(this);
                Config config=session.getConfig();
                boolean depth=session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
                if(depth)config.setDepthMode(Config.DepthMode.AUTOMATIC);
                session.configure(config);
                renderer.setSession(session,depth);
                detail.setText(depth?"ARCore Depth API available":"ARCore tracking available; Depth API not available");
            }
            if(scanning)return;
            memoryView.deactivate();memoryView.setVisibility(View.GONE);
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
        if(map.count()<35){
            status.setText("NOT ENOUGH 3D POINTS");
            detail.setText("Scan a well-lit textured space and collect more points first.");
            return;
        }
        boolean saved=map.save();
        status.setText(saved?"3D ROOM MEMORY SAVED":"COULD NOT SAVE MAP");
        detail.setText(saved?map.count()+" world points stored locally. Open DARK MEMORY to view.":
            "Map unavailable or storage error.");
    }
    private void openMemory(){
        if(scanning)stopScan();
        if(map.count()<35 && map.exists())map.load();
        memoryView.setVisibility(View.VISIBLE);
        memoryView.activate(map);
        warning.setText("POSITION FROZEN · DO NOT WALK USING THIS MAP");
        status.setText(map.count()+" SAVED POINTS · MEMORY VIEW");
        detail.setText("Phone orientation rotates the saved view. Your footsteps cannot be tracked here.");
        if(map.count()<35)status.setText("NO USABLE 3D MAP — SCAN FIRST");
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] result){
        super.onRequestPermissionsResult(request,permissions,result);
        if(request==CAMERA_REQUEST && result.length>0 &&result[0]==PackageManager.PERMISSION_GRANTED)
            startScan();
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
        }
        if(memoryView!=null)memoryView.deactivate();
        super.onPause();
    }
    @Override protected void onDestroy(){
        stopScan();
        if(session!=null)try{session.close();}catch(Throwable ignored){}
        if(memoryView!=null)memoryView.deactivate();
        super.onDestroy();
    }
}
