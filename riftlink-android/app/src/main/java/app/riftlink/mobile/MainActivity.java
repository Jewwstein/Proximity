package app.riftlink.mobile;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.graphics.SurfaceTexture;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    FrameLayout root, connectionPanel;
    TextureView video;
    ControlOverlay controls;
    RiftNet net;
    StreamDecoder decoder;
    TextView status;
    EditText hostBox, pinBox;
    LinearLayout menu;
    float zoom=1f, panX=0f, panY=0f; boolean audioEnabled=true;
    int desktopWidth=1920,desktopHeight=1080;
    Handler ui = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5,9,18));
        video=new TextureView(this);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){ fitVideo(); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){ fitVideo(); }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t){ stopVideo(); return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture t){}
        });
        root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        controls=new ControlOverlay(this, (action,key,x,y)->{
            if(net!=null) net.input(action,key,x,y);
        }, (name,key)->editMapping(name,key));
        root.addView(controls,new FrameLayout.LayoutParams(-1,-1));
        status=new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0x99050813);
        status.setTextSize(11);
        status.setText("RiftLink");
        FrameLayout.LayoutParams sl=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);
        sl.setMargins(dp(8),dp(8),0,0);
        root.addView(status,sl);
        Button settings=button("☰");
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(48),dp(42),Gravity.TOP|Gravity.RIGHT);
        sp.setMargins(0,dp(7),dp(8),0);
        root.addView(settings,sp);
        settings.setOnClickListener(v->toggleMenu());
        createMenu();
        createConnectionPanel();
        setContentView(root);
    }
    void immersive(){
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    Button button(String t){
        Button b=new Button(this);b.setText(t);b.setTextColor(Color.WHITE);
        b.setTextSize(13); b.setAllCaps(false); b.setBackgroundTintList(
          android.content.res.ColorStateList.valueOf(0xFF17345B));return b;
    }
    void createMenu(){
        menu=new LinearLayout(this);menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(6),dp(6),dp(6),dp(6));menu.setBackgroundColor(0xEA071626);
        addMenu("Desktop / Controller",()->{controls.game=!controls.game;controls.invalidate(); menu.setVisibility(View.GONE);});
        addMenu("Edit control positions",()->{controls.edit=!controls.edit; controls.game=true;
            controls.invalidate(); menu.setVisibility(View.GONE);toast(controls.edit?"Drag buttons to move; tap to change key":"Layout saved");});
        addMenu("Show / Hide buttons",()->{controls.showButtons=!controls.showButtons; controls.invalidate();menu.setVisibility(View.GONE);});
        addMenu("Button opacity +",()->{controls.opacity=Math.min(240,controls.opacity+25); controls.save();controls.invalidate();});
        addMenu("Button opacity −",()->{controls.opacity=Math.max(35,controls.opacity-25); controls.save();controls.invalidate();});
        addMenu("Zoom +",()->{onPinch(1.25f);});
        addMenu("Zoom −",()->{onPinch(.8f);});
        addMenu("Fit screen",()->{zoom=1f;panX=0;panY=0;fitVideo();menu.setVisibility(View.GONE);});
        addMenu("Enlarge selected control",()->controls.resizeSelected(1.18f));
        addMenu("Shrink selected control",()->controls.resizeSelected(.84f));
        addMenu("Type on PC",this::typeOnPC);
        addMenu("Sound on / off",()->{audioEnabled=!audioEnabled;if(net!=null){net.stopAudio();if(audioEnabled)net.startAudio(s->runOnUiThread(()->status.setText(s)));}toast(audioEnabled?"Audio on":"Audio off");});
        addMenu("Reconnect",()->{menu.setVisibility(View.GONE);reconnect();});
        addMenu("Disconnect",()->{menu.setVisibility(View.GONE);stopVideo();connectionPanel.setVisibility(View.VISIBLE);});
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(220),-2,Gravity.TOP|Gravity.RIGHT);
        p.setMargins(0,dp(53),dp(8),0);
        root.addView(menu,p);menu.setVisibility(View.GONE);
    }
    void addMenu(String label,Runnable action){
        Button b=button(label);menu.addView(b,new LinearLayout.LayoutParams(-1,dp(44)));
        b.setOnClickListener(v->action.run());
    }
    void toggleMenu(){menu.setVisibility(menu.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);}
    void createConnectionPanel(){
        connectionPanel=new FrameLayout(this);connectionPanel.setBackgroundColor(0xF5071220);
        LinearLayout col=new LinearLayout(this);col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22),dp(20),dp(22),dp(20));col.setBackgroundColor(0xFF10233F);
        TextView title=new TextView(this);title.setText("RIFTLINK  •  NATIVE ANDROID"); title.setTextColor(Color.WHITE);title.setTextSize(20);
        col.addView(title);
        TextView sub=new TextView(this);sub.setText("Enter the Wi-Fi IP shown by your Windows RiftLink host. Video uses Android's hardware decoder.");
        sub.setTextColor(0xFFBBCAE4);sub.setTextSize(12);col.addView(sub);
        hostBox=new EditText(this);hostBox.setSingleLine(true);hostBox.setTextColor(Color.WHITE);
        hostBox.setHintTextColor(0xFF99B2CF);hostBox.setHint("PC IP only — no colon or port needed");
        hostBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        hostBox.setText(getPreferences(0).getString("host",""));
        col.addView(hostBox);
        pinBox=new EditText(this);pinBox.setSingleLine(true);pinBox.setTextColor(Color.WHITE);
        pinBox.setHintTextColor(0xFF99B2CF);pinBox.setHint("6-digit pairing PIN (first connection)");
        pinBox.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);col.addView(pinBox);
        Button connect=button("Connect to PC");col.addView(connect);
        connect.setOnClickListener(v->beginConnect());
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.min(dp(470),getResources().getDisplayMetrics().widthPixels-dp(24)),-2,Gravity.CENTER);
        connectionPanel.addView(col,p);root.addView(connectionPanel,new FrameLayout.LayoutParams(-1,-1));
    }
    void beginConnect(){
        String host=hostBox.getText().toString().trim().replace("https://","").replace("http://","");
        host=host.replaceAll("/.*$","").replaceAll(":38775$","");
        if(!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")){toast("Enter your PC's local IPv4 address");return;}
        final String chosen=host;
        getPreferences(0).edit().putString("host",chosen).apply();
        status.setText("Checking host certificate…");
        net=new RiftNet(this,chosen);
        net.discoverFingerprint(new RiftNet.Discovery(){
            public void error(String e){runOnUiThread(()->{status.setText("Connection failed: "+e);toast(e);});}
            public void fingerprint(String fp,boolean known){
                runOnUiThread(()->{
                    if(known){startAuthorizedConnection();}
                    else new AlertDialog.Builder(MainActivity.this)
                       .setTitle("Trust this PC?")
                       .setMessage("Only continue if this is your computer at "+chosen+
                          ".\n\nTLS certificate SHA-256:\n"+fp+
                          "\n\nRiftLink will remember this exact certificate for future connections.")
                       .setPositiveButton("Trust & connect",(d,w)->{net.pin(fp);startAuthorizedConnection();})
                       .setNegativeButton("Cancel",null).show();
                });
            }
        });
    }
    void startAuthorizedConnection(){
        status.setText("Pairing and checking video…");
        String pin=pinBox.getText().toString().trim();
        String savedHost=hostBox.getText().toString().trim();
        String savedToken=getPreferences(0).getString("token-"+net.host,"");
        net.authenticate(pin,savedToken,new RiftNet.AuthListener(){
            public void complete(String token,int w,int h){
                runOnUiThread(()->{
                    desktopWidth=w;desktopHeight=h;
                    getPreferences(0).edit().putString("token-"+net.host,token).apply();
                    connectionPanel.setVisibility(View.GONE);
                    controls.reset();zoom=1;panX=0;panY=0;fitVideo();status.setText("Connecting video…");
                    net.connectInputs();
                    startVideo();
                });
            }
            public void error(String error){runOnUiThread(()->{status.setText("Connect failed: "+error);toast(error);});}
        });
    }
    void startVideo(){
        if(net==null)return;
        if(!video.isAvailable()){ui.postDelayed(this::startVideo,120);return;}
        stopDecoder();
        decoder=new StreamDecoder(new Surface(video.getSurfaceTexture()),desktopWidth,desktopHeight,
            s->runOnUiThread(()->status.setText(s)));
        net.startStream(decoder, s->runOnUiThread(()->status.setText(s)));
        if(audioEnabled)net.startAudio(s->runOnUiThread(()->status.setText(s)));
    }
    void fitVideo(){
        if(video==null||video.getWidth()==0)return;
        float viewW=video.getWidth(),viewH=video.getHeight();
        float contentAspect=(float)desktopWidth/Math.max(1,desktopHeight);
        float viewAspect=viewW/viewH;
        float drawnW=viewW,drawnH=viewH;
        if(contentAspect>viewAspect)drawnH=viewW/contentAspect;else drawnW=viewH*contentAspect;
        Matrix m=new Matrix();m.setScale((drawnW/viewW)*zoom,(drawnH/viewH)*zoom,viewW/2f,viewH/2f);
        m.postTranslate(panX,panY);
        video.setTransform(m);
        if(controls!=null){controls.videoBoxW=drawnW*zoom;controls.videoBoxH=drawnH*zoom;}
    }
    void onPinch(float factor){
        float newZoom=Math.max(1f,Math.min(5f,zoom*factor));
        if(newZoom==1f){panX=0;panY=0;}zoom=newZoom;fitVideo();
    }
    void panZoom(float dx,float dy){
        if(zoom<=1f)return;
        panX=Math.max(-video.getWidth()*(zoom-1)/2f,Math.min(video.getWidth()*(zoom-1)/2f,panX+dx));
        panY=Math.max(-video.getHeight()*(zoom-1)/2f,Math.min(video.getHeight()*(zoom-1)/2f,panY+dy));
        fitVideo();
    }
    void typeOnPC(){
        EditText box=new EditText(this);
        box.setSingleLine(false);box.setTextColor(Color.BLACK);box.setHint("Text to type on Windows");
        new AlertDialog.Builder(this).setTitle("Windows keyboard").setView(box)
            .setPositiveButton("Send",(dialog,which)->{
               if(net!=null)net.input("text",box.getText().toString(),.5f,.5f);
               menu.setVisibility(View.GONE);
            }).setNegativeButton("Cancel",null).show();
    }
    void reconnect(){if(net!=null){stopDecoder();net.stopStream();startVideo();}}
    void stopDecoder(){if(decoder!=null){decoder.close();decoder=null;}}
    void stopVideo(){stopDecoder();if(net!=null){net.stop();net=null;}}
    void editMapping(String name,String current){
        EditText key=new EditText(this);key.setSingleLine(true);key.setText(current);
        new AlertDialog.Builder(this).setTitle("Key for "+name)
            .setMessage("Keyboard key: W, A, S, D, SPACE, SHIFT, CTRL, E, F, R, Q, TAB, ESC")
            .setView(key).setPositiveButton("Save",(d,w)->controls.setMapping(name,key.getText().toString().trim().toUpperCase(Locale.ROOT)))
            .setNegativeButton("Cancel",null).show();
    }
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(menu.getVisibility()==View.VISIBLE){menu.setVisibility(View.GONE);}else toggleMenu();}
    @Override protected void onDestroy(){stopVideo();super.onDestroy();}
}
