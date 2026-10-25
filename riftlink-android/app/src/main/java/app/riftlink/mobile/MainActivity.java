package app.riftlink.mobile;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
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
    PhoneBridge phoneBridge;
    StreamDecoder decoder;
    TextView status; Button menuButton, fitButton, keyboardButton; boolean hudHidden=false;
    EditText hostBox, pinBox;
    LinearLayout menu; ScrollView menuScroller; LiveKeyboardPanel liveKeyboard;
    float zoom=1f, panX=0f, panY=0f; boolean audioEnabled=true;
    int desktopWidth=1920,desktopHeight=1080;
    Handler ui = new Handler(Looper.getMainLooper());
    // Only one active video session may own a TextureView surface. These fields
    // are controlled on the Android UI thread; input/audio remain independent.
    long videoSessionGeneration=0;
    RiftNet videoSessionNet;
    Runnable videoWatchdog;
    long videoSessionStartedAtMs=0;
    volatile long lastUserInteractionAtMs=0;
    RiftLinkUpgrade upgrade;


    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        root=new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video=new TextureView(this);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){
                fitVideo();
                // Android can recreate the TextureView during a mode/orientation
                // transition. Resume video automatically, retaining input/audio.
                if(net!=null && net.running && !net.token.isEmpty()
                    && videoSessionNet==null)startVideo();
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){ fitVideo(); }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t){
                // Do NOT discard pairing, input, audio or the selected PC when a
                // TextureView surface is temporarily destroyed.
                stopVideoPipeline();
                return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture t){}
        });
        root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        controls=new ControlOverlay(this, (action,key,x,y)->{
            lastUserInteractionAtMs=SystemClock.elapsedRealtime();
            if(net!=null) net.input(action,key,x,y);
        }, (name,key)->editMapping(name,key));
        root.addView(controls,new FrameLayout.LayoutParams(-1,-1));
        status=new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xDB000000);
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
        upgrade=new RiftLinkUpgrade(this);
        createMenu();
        createConnectionPanel();
        liveKeyboard=new LiveKeyboardPanel(this,root,this::toast);
        FrameLayout.LayoutParams keyboardParams=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
        keyboardParams.setMargins(dp(6),dp(54),dp(6),0);
        root.addView(liveKeyboard,keyboardParams);
        setContentView(root);
        applyUserOrientationMode();
        root.post(this::resizeOrientationUI);
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
        b.setTextSize(13); b.setAllCaps(false);
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF07121E);bg.setCornerRadius(dp(12));bg.setStroke(dp(1),0xFF278CD6);
        b.setBackground(bg);b.setElevation(dp(3));return b;
    }
    void createMenu(){
        menu=new LinearLayout(this);menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(6),dp(6),dp(6),dp(6));menu.setBackgroundColor(0xF9000307);
        addMenu("Game Mode • launch PC games",()->upgrade.showGameLibrary());
        addMenu("Desktop Mode • original controls",()->upgrade.desktop());
        addMenu("Switch RiftLink PC",()->upgrade.discover(false));
        addMenu("Screen rotation • Auto / Portrait / Landscape",this::chooseOrientation);
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
                new AlertDialog.Builder(this).setTitle("Two-thumb FPS • complete controls")
                    .setMessage("LEFT STICK: move; double tap to toggle ADS.\nRIGHT STICK: aim; double tap to toggle FIRE.\nYou can keep both thumbs on the sticks during play.\n\nJump, slide, reload, interact, shoulder controls, stick-clicks, Start and Back are also on screen.\n\nEnable Xbox gamepad support on the PC for held triggers and full analog movement.")
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
        addMenu("Test mouse-wheel scrolling",()->{
            menuScroller.setVisibility(View.GONE);
            if(net==null){toast("Connect to PC first");return;}
            new AlertDialog.Builder(this).setTitle("RiftLink scroll diagnostic")
              .setMessage("Choose a direction to send a real Windows mouse-wheel event. If this works, the host and input protocol are working.")
              .setPositiveButton("Scroll down",(dialog,which)->net.scroll(-360))
              .setNegativeButton("Scroll up",(dialog,which)->net.scroll(360))
              .setNeutralButton("Cancel",null).show();
        });
        addMenu("Zoom +",()->{onPinch(1.25f);});
        addMenu("Zoom −",()->{onPinch(.8f);});
        addMenu("Fit screen",()->{zoom=1f;panX=0;panY=0;fitVideo();menuScroller.setVisibility(View.GONE);});
        addMenu("Enlarge selected control",()->controls.resizeSelected(1.18f));
        addMenu("Shrink selected control",()->controls.resizeSelected(.84f));
        addMenu("Live keyboard • instant typing",this::typeOnPC);
        addMenu("Choose phone folder for Windows dashboard",()->{
            menuScroller.setVisibility(View.GONE);
            if(phoneBridge==null){toast("Connect to your PC first");return;}
            phoneBridge.pickSharedFolder();
        });
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
    void applyUserOrientationMode(){
        String mode=getPreferences(0).getString("screenMode","auto");
        int choice=mode.equals("portrait")?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
            mode.equals("landscape")?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        if(getRequestedOrientation()!=choice)setRequestedOrientation(choice);
    }
    void chooseOrientation(){
        menuScroller.setVisibility(View.GONE);
        final String[] labels={"Automatic rotation","Portrait (vertical)","Landscape (horizontal)"};
        final String[] modes={"auto","portrait","landscape"};
        String current=getPreferences(0).getString("screenMode","auto");
        int selected=current.equals("portrait")?1:current.equals("landscape")?2:0;
        new AlertDialog.Builder(this).setTitle("RiftLink screen orientation")
            .setSingleChoiceItems(labels,selected,(dialog,index)->{
                getPreferences(0).edit().putString("screenMode",modes[index]).apply();
                if(liveKeyboard!=null && liveKeyboard.isShowing())liveKeyboard.hide();
                dialog.dismiss();
                applyUserOrientationMode();
            }).setNegativeButton("Cancel",null).show();
    }
    @Override public void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);
        if(controls!=null)controls.onOrientationChanged(
            configuration.orientation==Configuration.ORIENTATION_PORTRAIT);
        if(liveKeyboard!=null && liveKeyboard.isShowing())liveKeyboard.hide();
        if(menuScroller!=null)menuScroller.setVisibility(View.GONE);
        // Orientation changes retain the surface, stream and saved authentication.
        zoom=1f;panX=0f;panY=0f;
        if(root!=null)root.post(this::resizeOrientationUI);
    }
    void resizeOrientationUI(){
        if(root==null)return;
        int width=root.getWidth(),height=root.getHeight();
        if(width<=0||height<=0)return;
        boolean portrait=height>width;
        if(menuScroller!=null){
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)menuScroller.getLayoutParams();
            lp.width=Math.min(dp(244),Math.max(dp(180),width-dp(16)));
            lp.height=Math.max(dp(110),height-dp(62));
            menuScroller.setLayoutParams(lp);
        }
        if(connectionPanel!=null && connectionPanel.getChildCount()>0){
            View panel=connectionPanel.getChildAt(0);
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)panel.getLayoutParams();
            lp.width=Math.min(dp(470),Math.max(dp(220),width-dp(24)));
            panel.setLayoutParams(lp);
        }
        if(status!=null){
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)status.getLayoutParams();
            lp.topMargin=portrait?dp(53):dp(8);
            status.setMaxWidth(Math.max(dp(150),width-dp(24)));
            status.setLayoutParams(lp);
        }
        fitVideo();
        if(controls!=null)controls.invalidate();
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
        connectionPanel=new FrameLayout(this);connectionPanel.setBackgroundColor(0xFA000000);
        LinearLayout col=new LinearLayout(this);col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22),dp(20),dp(22),dp(20));col.setBackgroundColor(0xFF080D13);
        TextView title=new TextView(this);title.setText("RIFTLINK   /   STUDIO"); title.setTextColor(0xFFE9F5FF);title.setTextSize(22);
        title.setShadowLayer(dp(7),0,dp(2),0xFF168CFF);
        col.addView(title);
        TextView sub=new TextView(this);sub.setText("Your V2.2 fast streaming, full FPS controls, keyboard and audio — now with automatic PC pairing.");
        sub.setTextColor(0xFFBBCAE4);sub.setTextSize(12);col.addView(sub);
        TextView modeTitle=new TextView(this);
        modeTitle.setText("  YOUR RIFTLINK SESSIONS");
        modeTitle.setTextColor(0xFF2C9EFF);modeTitle.setPadding(0,dp(14),0,dp(8));
        col.addView(modeTitle);
        Button desktop=button("DESKTOP MODE     •     Find PCs");
        col.addView(desktop);
        desktop.setOnClickListener(v->upgrade.discover(false));
        Button gaming=button("GAME MODE       •     Find PC games");
        col.addView(gaming);
        gaming.setOnClickListener(v->upgrade.discover(true));
        TextView manual=new TextView(this);
        manual.setText("Advanced connection • IP address / older host PIN");
        manual.setTextColor(0xFF8DA8BD);manual.setPadding(0,dp(14),0,dp(2));
        col.addView(manual);
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
        stopVideoPipeline();
        connectionPanel.setVisibility(View.VISIBLE);
        pinBox.setText("");
        status.setText("Pairing expired — enter the PC PIN once");
        toast("Host identity changed; pair once to save the new login.");
    }
    void closeKeyboardForGesture(){
        if(liveKeyboard!=null && liveKeyboard.isShowing())liveKeyboard.hide();
    }
    void startAuthorizedConnection(){
        final RiftNet candidate=net;
        if(candidate==null)return;
        status.setText("Pairing and checking video…");
        String pin=pinBox.getText().toString().trim();
        String savedToken=getPreferences(0).getString("token-cert-"+candidate.fingerprint,"");
        if(savedToken.isEmpty())savedToken=getPreferences(0).getString("token-"+candidate.host,"");
        candidate.authenticate(pin,savedToken,new RiftNet.AuthListener(){
            public void complete(String token,int w,int h,boolean gamepad){
                runOnUiThread(()->{
                    // A previous PC's asynchronous authorization must not
                    // seize a newly selected connection or video surface.
                    if(net!=candidate || !candidate.running || isFinishing())return;
                    desktopWidth=w;desktopHeight=h;
                    getPreferences(0).edit().putString("token-"+candidate.host,token)
                        .putString("token-cert-"+candidate.fingerprint,token).apply();
                    connectionPanel.setVisibility(View.GONE);
                    controls.reset();controls.useXInput=gamepad;
                    zoom=1;panX=0;panY=0;fitVideo();
                    status.setText("Connecting video…");
                    candidate.connectInputs();
                    if(phoneBridge!=null)phoneBridge.stop();
                    phoneBridge=new PhoneBridge(MainActivity.this,candidate);
                    phoneBridge.start();
                    // Audio/file transfer start once per authorized PC. A
                    // video-only recovery must never restart or multiply them.
                    startVideo();
                    candidate.startFileAutoSync(MainActivity.this::showTransferMessage);
                    if(audioEnabled)candidate.startAudio(
                        msg->runOnUiThread(()->{
                            if(net==candidate)status.setText(msg);
                        }));
                    upgrade.afterConnected();
                });
            }
            public void error(String error){
                runOnUiThread(()->{
                    if(net==candidate){status.setText("Connect failed: "+error);toast(error);}
                });
            }
        });
    }

    void stopVideoPipeline(){
        // Stop the old socket reader FIRST; only then release MediaCodec/Surface.
        videoSessionGeneration++;
        if(videoWatchdog!=null)ui.removeCallbacks(videoWatchdog);
        videoWatchdog=null;
        RiftNet previous=videoSessionNet;
        videoSessionNet=null;
        if(previous!=null)previous.stopStream();
        stopDecoder();
    }

    void startVideo(){
        if(Looper.myLooper()!=Looper.getMainLooper()){
            runOnUiThread(this::startVideo);return;
        }
        final RiftNet target=net;
        if(target==null||!target.running||target.token.isEmpty())return;
        stopVideoPipeline();
        final long generation=videoSessionGeneration;
        if(video==null||!video.isAvailable()){
            // Keep the selected PC and controls alive while Android recreates
            // its display surface. A stale delayed retry must never win.
            ui.postDelayed(()->{
                if(net==target && videoSessionGeneration==generation)
                    startVideo();
            },300);
            return;
        }
        try {
            final StreamDecoder next=new StreamDecoder(
                new Surface(video.getSurfaceTexture()),desktopWidth,desktopHeight,
                msg->runOnUiThread(()->{
                    if(net==target && videoSessionGeneration==generation)
                        status.setText(msg);
                }));
            decoder=next;
            videoSessionNet=target;
            videoSessionStartedAtMs=SystemClock.elapsedRealtime();
            target.startStream(next,
                msg->runOnUiThread(()->{
                    if(net==target && videoSessionGeneration==generation)
                        status.setText(msg);
                }),
                size->runOnUiThread(()->{
                    if(net!=target||videoSessionGeneration!=generation)return;
                    if(desktopWidth!=size[0]||desktopHeight!=size[1]){
                        desktopWidth=size[0];desktopHeight=size[1];
                        fitVideo();
                    }
                }));
            startVideoWatchdog(target,next,generation);
        }catch(Exception e){
            status.setText("Video surface unavailable: "+e.getMessage());
            stopVideoPipeline();
            // Only video is affected; input and audio remain connected.
        }
    }

    void startVideoWatchdog(final RiftNet target,final StreamDecoder active,final long generation){
        videoWatchdog=new Runnable(){
            @Override public void run(){
                if(net!=target||!target.running||!target.streaming ||
                   videoSessionGeneration!=generation || decoder!=active ||
                   !video.isAvailable())return;
                long now=SystemClock.elapsedRealtime();
                long lastRendered=active.lastPresentedAtMs;
                long lastPacket=target.lastVideoPacketAtMs;
                long sessionAge=now-videoSessionStartedAtMs;
                boolean noFirstFrame=lastRendered==0 && sessionAge>13000;
                boolean inputActive=lastUserInteractionAtMs>0 &&
                    now-lastUserInteractionAtMs<13000;
                boolean freshPackets=lastPacket>0 && now-lastPacket<9500;
                boolean pictureStale=lastRendered>0 && now-lastRendered>11000;
                // A static desktop can legitimately remain unchanged. Recover
                // only when the decoder is not producing despite active frames
                // or recent user input; never drop video for an idle scene.
                boolean videoFrozen=pictureStale&&(inputActive||freshPackets);
                if(noFirstFrame||videoFrozen){
                    status.setText("Restoring RiftLink video • controls stay connected");
                    startVideo();
                    return;
                }
                ui.postDelayed(this,2200);
            }
        };
        ui.postDelayed(videoWatchdog,2200);
    }

    /** Called when Desktop/Game Mode changes without switching to another PC. */
    void ensureVideoAlive(){
        if(Looper.myLooper()!=Looper.getMainLooper()){
            runOnUiThread(this::ensureVideoAlive);return;
        }
        if(net!=null&&net.running&&!net.token.isEmpty()&&
           video!=null&&video.isAvailable()&&
           (decoder==null||videoSessionNet!=net))
            startVideo();
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
    float scrollAccumulator=0f;
    void twoFingerScroll(float dy){
        if(net==null)return;
        scrollAccumulator+=dy;
        if(Math.abs(scrollAccumulator)>=15f){
            int steps=(int)(scrollAccumulator/15f);
            net.scroll(steps*120);
            scrollAccumulator-=steps*15f;
        }
    }
    void followCursor(float cx,float cy){
        if(zoom<=1.01f||video==null||controls==null||controls.game)return;
        float w=video.getWidth(),h=video.getHeight();
        if(w<=0||h<=0)return;
        float aspect=(float)desktopWidth/Math.max(1,desktopHeight);
        float drawnW=(w/h)>aspect?h*aspect:w;
        float drawnH=(w/h)>aspect?h:w/aspect;
        float x=w/2f+(cx-.5f)*drawnW*zoom+panX;
        float y=h/2f+(cy-.5f)*drawnH*zoom+panY;
        float dx=0,dy=0;
        if(x<w*.16f)dx=Math.min(25,w*.16f-x);
        if(x>w*.84f)dx=-Math.min(25,x-w*.84f);
        if(y<h*.16f)dy=Math.min(25,h*.16f-y);
        if(y>h*.84f)dy=-Math.min(25,y-h*.84f);
        if(dx!=0||dy!=0)panZoom(dx,dy);
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
        if(request==PhoneBridge.PICK_SHARED_TREE && result==RESULT_OK && phoneBridge!=null){
            phoneBridge.selectedTree(data);return;
        }
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
    void reconnect(){
        if(net!=null&&!net.token.isEmpty()){
            status.setText("Reconnecting video only • controls stay live");
            startVideo();
        }
    }
    void stopDecoder(){if(decoder!=null){decoder.close();decoder=null;}}
    void stopVideo(){
        stopVideoPipeline();
        if(upgrade!=null)upgrade.stop();
        if(liveKeyboard!=null)liveKeyboard.hide();
        if(phoneBridge!=null){phoneBridge.stop();phoneBridge=null;}
        if(net!=null){RiftNet departing=net;net=null;departing.stop();}
    }
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
