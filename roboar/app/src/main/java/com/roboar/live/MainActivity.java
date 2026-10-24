package com.roboar.live;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;

public class MainActivity extends Activity {
    private Session session;
    private GLSurfaceView surface;
    private ARRenderer renderer;
    private TextView status;
    private Button transform;
    private boolean installPrompted = false;
    private boolean surfaceRunning = false;
    private int colorIndex = 0;
    private final float[][] palette = {{0.13f,0.42f,0.98f}, {0.85f,0.11f,0.16f}, {0.12f,0.13f,0.17f}, {0.84f,0.81f,0.72f}, {0.38f,0.13f,0.68f}};

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        FrameLayout root = new FrameLayout(this);
        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(2);
        surface.setPreserveEGLContextOnPause(true);
        renderer = new ARRenderer(this);
        surface.setRenderer(renderer);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(surface, new FrameLayout.LayoutParams(-1,-1));
        status = new TextView(this);
        status.setText("RoboAR • Move slowly to scan the ground. Tap the ground near your car.");
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setPadding(dp(18),dp(30),dp(18),dp(16));
        status.setBackgroundColor(0xAA111526);
        FrameLayout.LayoutParams st = new FrameLayout.LayoutParams(-1,-2, Gravity.TOP);
        root.addView(status,st);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setBackgroundColor(0xB10D1220);
        actions.setPadding(dp(8),dp(8),dp(8),dp(20));
        transform = button("TRANSFORM", () -> {
            boolean toRobot = renderer.toggleTransform();
            transform.setText(toRobot ? "RETURN TO CAR" : "TRANSFORM");
        });
        actions.addView(transform, new LinearLayout.LayoutParams(-1,dp(55)));
        LinearLayout secondary = new LinearLayout(this);
        secondary.setGravity(Gravity.CENTER);
        secondary.addView(button("PAINT", () -> { colorIndex = (colorIndex+1)%palette.length; renderer.setColor(palette[colorIndex]); }),new LinearLayout.LayoutParams(0,dp(48),1));
        secondary.addView(button("− SIZE", () -> renderer.scaleBy(0.8f)),new LinearLayout.LayoutParams(0,dp(48),1));
        secondary.addView(button("+ SIZE", () -> renderer.scaleBy(1.25f)),new LinearLayout.LayoutParams(0,dp(48),1));
        secondary.addView(button("RESET", () -> {renderer.reset(); transform.setText("TRANSFORM");}),new LinearLayout.LayoutParams(0,dp(48),1));
        actions.addView(secondary, new LinearLayout.LayoutParams(-1,dp(50)));
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        root.addView(actions,ap);
        surface.setOnTouchListener((v,e) -> {
            if (e.getAction()==android.view.MotionEvent.ACTION_UP) {
                renderer.placeAt(e.getX(),e.getY());
                return true;
            }
            return true;
        });
        setContentView(root);
    }

    private Button button(String label, Runnable click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF3458A6));
        b.setOnClickListener(v -> click.run());
        return b;
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    void message(String s){runOnUiThread(()->status.setText("RoboAR • "+s));}
    Session getArSession(){return session;}
    int getDisplayRotation(){return getWindowManager().getDefaultDisplay().getRotation();}
    @Override protected void onResume(){
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},10);
            return;
        }
        try {
            if(session==null) {
                if(ArCoreApk.getInstance().requestInstall(this,!installPrompted)!=ArCoreApk.InstallStatus.INSTALLED) {
                    installPrompted=true;
                    message("Google Play Services for AR installation required.");
                    return;
                }
                session=new Session(this);
                Config config=session.getConfig();
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL);
                config.setFocusMode(Config.FocusMode.AUTO);
                session.configure(config);
            }
            session.resume();
            renderer.invalidateCameraTextureBinding();
            surface.onResume();
            surfaceRunning=true;
        } catch(Exception ex) {
            message("AR unavailable: "+ex.getClass().getSimpleName()+" — "+ex.getMessage());
        }
    }
    @Override protected void onPause(){
        super.onPause();
        if(surfaceRunning){surface.onPause();surfaceRunning=false;}
        if(session!=null) session.pause();
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(requestCode,permissions,grants);
        if(requestCode==10){
            if(grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED) onResume();
            else message("Camera permission is needed for the live AR scene.");
        }
    }
    @Override protected void onDestroy(){super.onDestroy(); if(session!=null){session.close(); session=null;}}
}
