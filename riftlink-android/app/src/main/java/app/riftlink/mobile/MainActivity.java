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
    TextView status; Button menuButton, fitButton, keyboardButton; boolean hudHidden=false;
    EditText hostBox, pinBox;
    LinearLayout menu; ScrollView menuScroller; LiveKeyboardPanel liveKeyboard;
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
        Button settings=button("☰");menuButton=settings;
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(48),dp(42),Gravity.TOP|Gravity.RIGHT);
        sp.setMargins(0,dp(7),dp(8),0);
        root.addView(settings,sp);
        settings.setOnClickListener(v->toggleMenu());
        fitButton=button("Fit");
        FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(53),dp(42),Gravity.TOP|Gravity.RIGHT);
        fp.setMargins(0,dp(7),dp(64),0);
        root.addView(fitButton,fp);
        fitButton.setOnClickListener(v->{zoom=1f;panX=0;panY=0;fitVideo();});
        keyboardButton=button("⌨");
        FrameLayout.LayoutParams kp=new FrameLayout.LayoutParams(dp(53),dp(42),Gravity.TOP|Gravity.RIGHT);
        kp.setMargins(0,dp(7),dp(120),0);
        root.addView(keyboardButton,kp);
        keyboardButton.setOnClickListener(v->{
            if(liveKeyboard!=null && liveKeyboard.isShowing())liveKeyboard.hide();
            else typeOnPC();
        });
        createMenu();
        createConnectionPanel();
        liveKeyboard=new LiveKeyboardPanel(this,root,this::toast);
        FrameLayout.LayoutParams keyboardParams=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
        keyboardParams.setMargins(dp(6),dp(54),dp(6),0);
        root.addView(liveKeyboard,keyboardParams);
        setContentView(root);
        // Already paired? Reconnect automatically when the app opens.
        String previousHost=getPreferences(0).getString("host","");
        if(!previousHost.isEmpty() && !getPreferences(0).getString("token-"+previousHost,"").isEmpty()){
            ui.postDelayed(()->{if(net==null)beginConnect();},550);
        }
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
        addMenu("Desktop / Controller",()->{
            controls.cancelAll();
            if(controls.fpsMode)controls.setFpsMode(false);
            if(controls.thumbMode)controls.setThumbMode(false);
            controls.game=!controls.game;controls.save();controls.invalidate();
            menuScroller.setVisibility(View.GONE);
        });
        addMenu("THUMB FPS • aim + fire on right stick",()->{
            boolean enable=!controls.thumbMode;
            controls.setThumbMode(enable);
            menuScroller.setVisibility(View.GONE);
            if(enable && !controls.useXInput){
                new AlertDialog.Builder(this).setTitle("Two-thumb shooter mode enabled")
                    .setMessage("MOVE with left thumb and LOOK with right. Lift and quickly re-touch the LOOK stick to FIRE while dragging. Two short taps toggle ADS.\n\nYour Windows host has not reported an Xbox gamepad, so real held ADS and shooter-style camera motion may require enabling the bundled Xbox controller in RiftLink Control Center.")
                    .setPositiveButton("Got it",null).show();
            }else toast(enable?"Thumb FPS ON • right re-grab shoots; two light taps ADS":"Thumb FPS OFF");
        });
        addMenu("Reset THUMB FPS layout",()->{
            controls.resetThumbPositions();menuScroller.setVisibility(View.GONE);
            toast("Two-thumb FPS positions restored");
        });
        addMenu("Optional: FPS claw controls",()->{
            boolean enable=!controls.fpsMode;
            controls.setFpsMode(enable);
            menuScroller.setVisibility(View.GONE);
            toast(enable?"FPS MODE: thumbs on MOVE/LOOK, index fingers on LT ADS and RT FIRE":
                "FPS MODE OFF");
        });
        addMenu("FPS ADS: hold / toggle",()->{
            controls.setAdsToggle(!controls.adsToggle);
            menuScroller.setVisibility(View.GONE);
            toast(controls.adsToggle?"ADS: tap LT to toggle (tap again to release)":"ADS: hold LT to aim");
        });
        addMenu("FPS right-stick sensitivity",this::chooseLookSensitivity);
        addMenu("Reset FPS button positions",()->{
            controls.resetFpsPositions();menuScroller.setVisibility(View.GONE);
            toast("FPS claw layout restored");
        });
        addMenu("Edit control positions",()->{controls.edit=!controls.edit; controls.game=true;
            controls.invalidate(); menuScroller.setVisibility(View.GONE);toast(controls.edit?"Drag buttons to move; tap to change key":"Layout saved");});
        addMenu("Show / Hide buttons",()->{controls.showButtons=!controls.showButtons; controls.save(); controls.invalidate();menuScroller.setVisibility(View.GONE);});
        addMenu("Hide all UI (3 fingers to restore)",()->{
            if(controls.game){toast("Hide controls only in Desktop mode to avoid interrupting thumb controls");menuScroller.setVisibility(View.GONE);}
            else toggleHud();
        });
        addMenu("Show / Hide FPS text",()->{status.setVisibility(status.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);menuScroller.setVisibility(View.GONE);});
        addMenu("Button opacity +",()->{controls.opacity=Math.min(240,controls.opacity+25); controls.save();controls.invalidate();});
        addMenu("Button opacity −",()->{controls.opacity=Math.max(35,controls.opacity-25); controls.save();controls.invalidate();});
        addMenu("Zoom +",()->{onPinch(1.25f);});
        addMenu("Zoom −",()->{onPinch(.8f);});
        addMenu("Fit screen",()->{zoom=1f;panX=0;panY=0;fitVideo();menuScroller.setVisibility(View.GONE);});
        addMenu("Enlarge selected control",()->controls.resizeSelected(1.18f));
        addMenu("Shrink selected control",()->controls.resizeSelected(.84f));
        addMenu("Live keyboard • instant typing",this::typeOnPC);
        addMenu("Send file to PC",this::pickPhoneFile);
        addMenu("Download file from PC",this::showRemoteFiles);
        addMenu("PC file auto-sync on/off",()->{
            if(net!=null){net.autoFileSyncEnabled=!net.autoFileSyncEnabled;
                if(net.autoFileSyncEnabled)net.startFileAutoSync(this::showTransferMessage);
                toast(net.autoFileSyncEnabled?"PC file auto-sync ON":"PC file auto-sync OFF");
            }menuScroller.setVisibility(View.GONE);
        });
        addMenu("Gamepad / connection status",()->{
            toast(controls.useXInput?"Xbox controller ready — LT/RT are analog triggers":
                "No Xbox controller on host. FPS joystick uses keyboard/mouse fallback; ADS hold needs Xbox mode.");
            menuScroller.setVisibility(View.GONE);
        });
        addMenu("Sound on / off",()->{audioEnabled=!audioEnabled;if(net!=null){net.stopAudio();if(audioEnabled)net.startAudio(s->runOnUiThread(()->status.setText(s)));}toast(audioEnabled?"Audio on":"Audio off");});
        addMenu("Reconnect",()->{menuScroller.setVisibility(View.GONE);reconnect();});
        addMenu("Disconnect",()->{menuScroller.setVisibility(View.GONE);stopVideo();connectionPanel.setVisibility(View.VISIBLE);});
        menuScroller=new ScrollView(this);
        menuScroller.setFillViewport(false);
        menuScroller.setVerticalScrollBarEnabled(true);
        menuScroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        menuScroller.addView(menu,new ScrollView.LayoutParams(-1,-2));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(244),
            Math.max(dp(160),getResources().getDisplayMetrics().heightPixels-dp(62)),Gravity.TOP|Gravity.RIGHT);
        p.setMargins(0,dp(53),dp(8),0);
        root.addView(menuScroller,p);menuScroller.setVisibility(View.GONE);
    }
    void chooseLookSensitivity(){
        menuScroller.setVisibility(View.GONE);
        final String[] choices={"Slow 0.65×","Normal 1.0×","Fast 1.45×","Very fast 2.0×"};
        final float[] values={.65f,1f,1.45f,2f};
        new AlertDialog.Builder(this).setTitle("FPS camera sensitivity")
            .setSingleChoiceItems(choices,1,(d,index)->{
                controls.setLookSensitivity(values[index]);d.dismiss();
                toast("Right stick sensitivity: "+choices[index]);
            }).setNegativeButton("Cancel",null).show();
    }
    void addMenu(String label,Runnable action){
        Button b=button(label);menu.addView(b,new LinearLayout.LayoutParams(-1,dp(44)));
        b.setOnClickListener(v->action.run());
    }
    void toggleHud(){
        hudHidden=!hudHidden;
        menuScroller.setVisibility(View.GONE);
        if(menuButton!=null)menuButton.setVisibility(hudHidden?View.GONE:View.VISIBLE);
        if(fitButton!=null)fitButton.setVisibility(hudHidden?View.GONE:View.VISIBLE);
        if(keyboardButton!=null)keyboardButton.setVisibility(hudHidden?View.GONE:View.VISIBLE);
        status.setVisibility(hudHidden?View.GONE:View.VISIBLE);
        controls.showButtons=!hudHidden;
        if(hudHidden && liveKeyboard!=null)liveKeyboard.hide();
        controls.invalidate();
        if(hudHidden)toast("Three fingers on screen to restore RiftLink controls");
    }
    void toggleMenu(){menuScroller.setVisibility(menuScroller.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);}
    void createConnectionPanel(){
        connectionPanel=new FrameLayout(this);connectionPanel.setBackgroundColor(0xF5071220);
        LinearLayout col=new LinearLayout(this);col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22),dp(20),dp(22),dp(20));col.setBackgroundColor(0xFF10233F);
        TextView title=new TextView(this);title.setText("RIFTLINK  •  NATIVE ANDROID"); title.setTextColor(Color.WHITE);title.setTextSize(20);
        col.addView(title);
        TextView sub=new TextView(this);sub.setText("Enter your PC IP on Wi-Fi or your secure private VPN. The port is filled automatically.");
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
        if(!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")){toast("Enter your PC IPv4 address (Wi-Fi or VPN)");return;}
        final String chosen=host;
        getPreferences(0).edit().putString("host",chosen).apply();
        status.setText("Checking host certificate…");
        if(net!=null)net.stop();
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
    void showExpiredPairing(){
        // Do not endlessly retry streams with rejected credentials.
        if(liveKeyboard!=null)liveKeyboard.hide();
        stopDecoder();
        connectionPanel.setVisibility(View.VISIBLE);
        pinBox.setText("");
        status.setText("Pairing expired — enter the PC PIN once");
        toast("Host identity changed; pair once to save the new login.");
    }
    void closeKeyboardForGesture(){
        if(liveKeyboard!=null && liveKeyboard.isShowing())liveKeyboard.hide();
    }
    void startAuthorizedConnection(){
        status.setText("Pairing and checking video…");
        String pin=pinBox.getText().toString().trim();
        String savedHost=hostBox.getText().toString().trim();
        String savedToken=getPreferences(0).getString("token-cert-"+net.fingerprint,"");
        if(savedToken.isEmpty())savedToken=getPreferences(0).getString("token-"+net.host,"");
        net.authenticate(pin,savedToken,new RiftNet.AuthListener(){
            public void complete(String token,int w,int h,boolean gamepad){
                runOnUiThread(()->{
                    desktopWidth=w;desktopHeight=h;
                    getPreferences(0).edit().putString("token-"+net.host,token)
                        .putString("token-cert-"+net.fingerprint,token).apply();
                    connectionPanel.setVisibility(View.GONE);
                    controls.reset();controls.useXInput=gamepad;zoom=1;panX=0;panY=0;fitVideo();status.setText("Connecting video…");
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
        net.startStream(decoder,
            message->runOnUiThread(()->status.setText(message)),
            size->runOnUiThread(()->{
                if(desktopWidth!=size[0]||desktopHeight!=size[1]){
                    desktopWidth=size[0];desktopHeight=size[1];
                    fitVideo();
                }
            }));
        net.startFileAutoSync(this::showTransferMessage);
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
        if(controls!=null){controls.videoBoxW=drawnW*zoom;controls.videoBoxH=drawnH*zoom;
            controls.cursorOffsetX=panX;controls.cursorOffsetY=panY;controls.invalidate();}
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
        menuScroller.setVisibility(View.GONE);
        if(net==null){toast("Connect to PC first");return;}
        if(liveKeyboard!=null)liveKeyboard.show();
    }
    static final int PICK_RIFTLINK_FILE=1297;
    void pickPhoneFile(){
        menuScroller.setVisibility(View.GONE);
        if(net==null){toast("Connect to PC first");return;}
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.setType("*/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(pick,PICK_RIFTLINK_FILE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_RIFTLINK_FILE&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            net.uploadFile(data.getData(),this::showTransferMessage);
        }
    }
    void showTransferMessage(String message){runOnUiThread(()->{status.setText(message);toast(message);});}
    void showRemoteFiles(){
        menuScroller.setVisibility(View.GONE);
        if(net==null){toast("Connect to PC first");return;}
        net.listFiles((names,error)->runOnUiThread(()->{
            if(error!=null){toast(error);return;}
            if(names.length==0){toast("No files in PC desktop RiftLink Exchange folder");return;}
            new AlertDialog.Builder(this).setTitle("Download from PC")
                .setItems(names,(dialog,index)->net.downloadFile(names[index],this::showTransferMessage))
                .setNegativeButton("Cancel",null).show();
        }));
    }
    void reconnect(){if(net!=null){stopDecoder();net.stopStream();startVideo();}}
    void stopDecoder(){if(decoder!=null){decoder.close();decoder=null;}}
    void stopVideo(){if(liveKeyboard!=null)liveKeyboard.hide();stopDecoder();if(net!=null){net.stop();net=null;}}
    void editMapping(String name,String current){
        EditText key=new EditText(this);key.setSingleLine(true);key.setText(current);
        new AlertDialog.Builder(this).setTitle("Key for "+name)
            .setMessage("Keyboard key: W, A, S, D, SPACE, SHIFT, CTRL, E, F, R, Q, TAB, ESC")
            .setView(key).setPositiveButton("Save",(d,w)->controls.setMapping(name,key.getText().toString().trim().toUpperCase(Locale.ROOT)))
            .setNegativeButton("Cancel",null).show();
    }
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){
        if(liveKeyboard!=null && liveKeyboard.isShowing()){liveKeyboard.hide();return;}
        if(menuScroller.getVisibility()==View.VISIBLE){menuScroller.setVisibility(View.GONE);}
        else toggleMenu();
    }
    @Override protected void onDestroy(){stopVideo();super.onDestroy();}
}
