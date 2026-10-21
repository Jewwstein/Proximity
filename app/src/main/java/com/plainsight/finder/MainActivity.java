package com.plainsight.finder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private static final int REQ_CAMERA=11,REQ_VOICE=12;
    private static final int CYAN=0xff83eaff,INK=0xff081323,WHITE=0xffefffff;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private PreviewView preview;
    private Overlay overlay;
    private LinearLayout bottom,teachPanel,keyPanel,savedList;
    private EditText query,learnName,keyField;
    private TextView status,learnCount;
    private Button searchButton,learnButton;
    private ImageView zoom;
    private boolean learning=false,searching=false;
    private int token=0,frames=0;
    private String learningItem="";
    private final ArrayList<String> angles=new ArrayList<>();
    private SharedPreferences prefs;
    private OfflineTextFinder localOcr;
    private volatile OfflineObjectFinder localObjects;
    private volatile PersonalMatcher personalMatcher;
    private FocusLockView focusLock;
    private int misses=0;

    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView label(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextColor(color);t.setTextSize(size);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private Button button(String s,int color,int ink){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);
        b.setTextSize(14);b.setTextColor(ink);b.setBackground(rounded(color,14));return b;
    }
    private void state(String s){ui.post(()->{if(status!=null)status.setText(s);});}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("plain-sight-gemini-v4",MODE_PRIVATE);
        getWindow().setStatusBarColor(INK);getWindow().setNavigationBarColor(INK);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_FULLSCREEN);
        constructUi();
        // This revision performs image analysis locally; never transmit camera frames.
        prefs.edit().remove("api_key").apply();
        localOcr=new OfflineTextFinder();
        worker.execute(()->{
            try{
                personalMatcher=new PersonalMatcher(this);
                localObjects=new OfflineObjectFinder(this);
                state("Local visual detector ready · all searches stay on your phone");
            }catch(Exception e){
                android.util.Log.e("PlainSight","Offline object model failed",e);
                state("Object AI failed to load: "+e.getMessage());
            }
        });
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);
        else connectCamera();
    }
    private void constructUi(){
        root=new FrameLayout(this);root.setBackgroundColor(INK);setContentView(root);
        preview=new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        overlay=new Overlay();
        root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        focusLock=new FocusLockView(this);
        root.addView(focusLock,new FrameLayout.LayoutParams(-1,-1));
        focusLock.setOnClickListener(v->{reset();state("Live camera resumed. Tap FIND to scan again.");});

        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(19),dp(21),dp(12),dp(10));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xee061320,0xbb061320,0x00061320}));
        root.addView(top,new FrameLayout.LayoutParams(-1,dp(159),Gravity.TOP));
        LinearLayout headline=new LinearLayout(this);headline.setGravity(Gravity.CENTER_VERTICAL);
        headline.addView(label("PLAIN SIGHT",21,CYAN,true),new LinearLayout.LayoutParams(0,dp(46),1));
        Button settings=button("AI KEY",0xff214054,CYAN);
        headline.addView(settings,new LinearLayout.LayoutParams(dp(73),dp(42)));
        settings.setVisibility(View.GONE);
        top.addView(headline,new LinearLayout.LayoutParams(-1,dp(46)));
        status=label("Starting camera…",13,WHITE,false);
        status.setMaxLines(4);
        top.addView(status,new LinearLayout.LayoutParams(-1,dp(89)));

        zoom=new ImageView(this);zoom.setScaleType(ImageView.ScaleType.FIT_CENTER);
        zoom.setBackground(rounded(0xf4071725,16));
        zoom.setPadding(dp(5),dp(5),dp(5),dp(5));
        FrameLayout.LayoutParams zp=new FrameLayout.LayoutParams(dp(150),dp(150),Gravity.TOP|Gravity.RIGHT);
        zp.topMargin=dp(162);zp.rightMargin=dp(14);
        root.addView(zoom,zp);zoom.setVisibility(View.GONE);

        bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(10),dp(12),dp(10),dp(25));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xff061320,0xed061320,0x00061320}));
        root.addView(bottom,new FrameLayout.LayoutParams(-1,dp(131),Gravity.BOTTOM));
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(7),dp(6),dp(7),dp(6));bar.setBackground(rounded(0xf51a3141,20));
        bottom.addView(bar,new LinearLayout.LayoutParams(-1,dp(66)));
        Button plus=button("+",0xff2c4d60,CYAN);plus.setTextSize(23);
        bar.addView(plus,new LinearLayout.LayoutParams(dp(44),dp(50)));
        plus.setOnClickListener(v->openLearn());
        query=new EditText(this);query.setSingleLine(true);query.setTextSize(15);
        query.setTextColor(WHITE);query.setHintTextColor(0xffb9c7cf);
        query.setHint("Find my keys…");query.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(0,dp(50),1);qp.leftMargin=dp(5);
        bar.addView(query,qp);
        query.setImeOptions(3);query.setOnEditorActionListener((t,a,e)->{search();return true;});
        Button mic=button("🎙",0xff2c4d60,Color.WHITE);mic.setTextSize(20);
        bar.addView(mic,new LinearLayout.LayoutParams(dp(45),dp(50)));
        mic.setOnClickListener(v->listen());
        searchButton=button("FIND",CYAN,INK);
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(68),dp(50));fp.leftMargin=dp(5);
        bar.addView(searchButton,fp);searchButton.setOnClickListener(v->search());
        makeTeach();
        makeKey();
    }
    private void makeTeach(){
        teachPanel=new LinearLayout(this);teachPanel.setOrientation(LinearLayout.VERTICAL);
        teachPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        teachPanel.setPadding(dp(19),dp(38),dp(19),dp(25));
        teachPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xfc071525,0xf0071525,0x88071525,0xf0071525,0xfc071525}));
        root.addView(teachPanel,new FrameLayout.LayoutParams(-1,-1));
        teachPanel.setVisibility(View.GONE);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label("LEARN MY OBJECT",20,CYAN,true),new LinearLayout.LayoutParams(0,dp(48),1));
        Button exit=button("✕",0xff254253,WHITE);
        row.addView(exit,new LinearLayout.LayoutParams(dp(48),dp(47)));
        exit.setOnClickListener(v->closeLearn());
        teachPanel.addView(row,new LinearLayout.LayoutParams(-1,dp(55)));

        learnName=new EditText(this);learnName.setSingleLine(true);learnName.setTextSize(17);
        learnName.setTextColor(WHITE);learnName.setHintTextColor(0xffb5cdd7);
        learnName.setHint("Item name: My key fob");
        learnName.setPadding(dp(14),0,dp(14),0);learnName.setBackground(rounded(0xff19374b,13));
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,dp(52));np.topMargin=dp(12);
        teachPanel.addView(learnName,np);
        TextView tip=label("Center only your item. Slowly rotate it while eight reference photographs are taken.",14,WHITE,false);
        tip.setGravity(Gravity.CENTER);
        teachPanel.addView(tip,new LinearLayout.LayoutParams(-1,dp(104)));
        teachPanel.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        learnCount=label("Hold your item in the square",14,CYAN,true);
        learnCount.setGravity(Gravity.CENTER);
        teachPanel.addView(learnCount,new LinearLayout.LayoutParams(-1,dp(38)));
        learnButton=button("LEARN 360°",CYAN,INK);
        learnButton.setOnClickListener(v->startLearning());
        teachPanel.addView(learnButton,new LinearLayout.LayoutParams(-1,dp(53)));
        TextView saved=label("SAVED OBJECTS · TAP TO FIND",12,0xffc4d9e2,true);saved.setGravity(Gravity.CENTER);
        teachPanel.addView(saved,new LinearLayout.LayoutParams(-1,dp(42)));
        ScrollView scroll=new ScrollView(this);savedList=new LinearLayout(this);savedList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(savedList);
        teachPanel.addView(scroll,new LinearLayout.LayoutParams(-1,dp(135)));
    }
    private void makeKey(){
        keyPanel=new LinearLayout(this);keyPanel.setOrientation(LinearLayout.VERTICAL);
        keyPanel.setPadding(dp(24),dp(60),dp(24),dp(25));keyPanel.setGravity(Gravity.TOP);
        keyPanel.setBackgroundColor(0xff071727);
        root.addView(keyPanel,new FrameLayout.LayoutParams(-1,-1));keyPanel.setVisibility(View.GONE);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label("ACTIVATE AI SEARCH",20,CYAN,true),new LinearLayout.LayoutParams(0,dp(50),1));
        Button close=button("✕",0xff244154,WHITE);
        head.addView(close,new LinearLayout.LayoutParams(dp(45),dp(45)));
        close.setOnClickListener(v->keyPanel.setVisibility(View.GONE));
        keyPanel.addView(head,new LinearLayout.LayoutParams(-1,dp(58)));
        TextView explanation=label(
          "Plain Sight now uses Gemini 3.8 Flash to find real objects by describing what you want. "+
          "You need your own Gemini API key for testing. It stays in this phone's private app storage. "+
          "FIND sends camera photos (and saved reference photos, if any) to Google for analysis. "+
          "Requests may use your API quota or incur charges.",15,WHITE,false);
        LinearLayout.LayoutParams xp=new LinearLayout.LayoutParams(-1,dp(190));xp.topMargin=dp(16);
        keyPanel.addView(explanation,xp);
        keyField=new EditText(this);keyField.setSingleLine(true);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setTextColor(WHITE);keyField.setHintTextColor(0xffbbc9d1);
        keyField.setHint("Paste Gemini API key");keyField.setTextSize(16);
        keyField.setPadding(dp(13),0,dp(13),0);keyField.setBackground(rounded(0xff234154,12));
        keyPanel.addView(keyField,new LinearLayout.LayoutParams(-1,dp(52)));
        Button save=button("SAVE KEY · ENABLE FIND",CYAN,INK);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(52));sp.topMargin=dp(15);
        keyPanel.addView(save,sp);
        save.setOnClickListener(v->{
            String s=keyField.getText().toString().trim();
            if(s.length()<15){keyField.setError("Paste a valid Gemini API key");return;}
            prefs.edit().putString("api_key",s).apply();
            keyField.setText("");keyPanel.setVisibility(View.GONE);
            state("AI ready. Point at an object and tap FIND.");
        });
        Button getKey=button("GET A GOOGLE AI STUDIO KEY ↗",0xff254356,CYAN);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(52));gp.topMargin=dp(14);
        keyPanel.addView(getKey,gp);
        getKey.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://aistudio.google.com/api-keys"))));
        TextView note=label("This is a developer test. A commercial release needs a protected server-side key, not a shared key inside an APK.",12,0xffb9cbd7,false);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(91));pp.topMargin=dp(20);
        keyPanel.addView(note,pp);
    }
    private void openKey(){
        reset();keyField.setText(prefs.getString("api_key",""));
        keyPanel.setVisibility(View.VISIBLE);
    }
    private void connectCamera(){
        ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{
            try{
                ProcessCameraProvider cameraProvider=future.get();cameraProvider.unbindAll();
                Preview p=new Preview.Builder().build();
                p.setSurfaceProvider(preview.getSurfaceProvider());
                cameraProvider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,p);
                state("Camera live · all searches are offline");
            }catch(Exception e){state("Camera failure: "+e.getMessage());}
        },ContextCompat.getMainExecutor(this));
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==REQ_CAMERA){
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)connectCamera();
            else state("Enable camera permission in Android settings.");
        }
    }
    private Bitmap frame(){return preview.getBitmap();}
    private static String encode(Bitmap image){
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.JPEG,78,out);
        return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
    }
    private static Bitmap decode(String encoded){
        byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);
        return BitmapFactory.decodeByteArray(bytes,0,bytes.length);
    }
    private String savedKey(String name){return "item:"+name.trim().toLowerCase(Locale.ROOT);}
    private void openLearn(){
        reset();keyPanel.setVisibility(View.GONE);
        learnName.setText("");learnCount.setText("Hold your item in the guide");
        teachPanel.setVisibility(View.VISIBLE);overlay.teach=true;overlay.invalidate();
        refreshSaved();
    }
    private void closeLearn(){
        if(learning)return;
        teachPanel.setVisibility(View.GONE);overlay.teach=false;overlay.invalidate();
    }
    private void refreshSaved(){
        savedList.removeAllViews();
        for(String key:prefs.getAll().keySet()){
            if(!key.startsWith("item:"))continue;
            String name=key.substring(5);
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView t=label(name,14,WHITE,false);row.addView(t,new LinearLayout.LayoutParams(0,dp(40),1));
            Button find=button("FIND",0xff244457,CYAN);
            row.addView(find,new LinearLayout.LayoutParams(dp(64),dp(34)));
            find.setOnClickListener(v->{query.setText(name);closeLearn();search();});
            Button remove=button("×",0xff512d40,0xffffb0ae);
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(34),dp(34));rp.leftMargin=dp(9);
            row.addView(remove,rp);
            remove.setOnClickListener(v->{prefs.edit().remove(key).apply();refreshSaved();});
            savedList.addView(row);
        }
        if(savedList.getChildCount()==0)savedList.addView(label("No learned items yet",12,0xffc8d5df,false));
    }
    private void startLearning(){
        if(learning)return;
        String name=learnName.getText().toString().trim();
        if(name.isEmpty()){learnName.setError("Name the item");return;}
        learning=true;learningItem=name;frames=0;angles.clear();learnButton.setEnabled(false);
        hideKeyboard(learnName);
        learnCount.setText("Rotate slowly · capturing images…");
        ui.postDelayed(this::captureAngle,700);
    }
    private void captureAngle(){
        if(!learning)return;
        Bitmap shot=frame();
        if(shot==null||shot.getWidth()<64){failLearning("Camera frame unavailable. Try again.");return;}
        int side=Math.round(Math.min(shot.getWidth(),shot.getHeight())*.56f);
        int x=(shot.getWidth()-side)/2,y=(shot.getHeight()-side)/2;
        worker.execute(()->{
            try{
                Bitmap square=Bitmap.createBitmap(shot,x,y,side,side);
                Bitmap resized=Bitmap.createScaledBitmap(square,512,512,true);
                String base64=encode(resized);
                square.recycle();resized.recycle();shot.recycle();
                ui.post(()->{
                    angles.add(base64);frames++;
                    learnCount.setText("Captured "+frames+" of 8 · turn your item");
                    if(frames<8)ui.postDelayed(this::captureAngle,620);
                    else{
                        JSONArray arr=new JSONArray();for(String s:angles)arr.put(s);
                        prefs.edit().putString(savedKey(learningItem),arr.toString()).apply();
                        learning=false;learnButton.setEnabled(true);
                        learnCount.setText("SAVED! Your item is ready for AI-assisted search.");
                        query.setText(learningItem);refreshSaved();
                        state("Saved 8 real photos of "+learningItem+".");
                    }
                });
            }catch(Exception e){
                android.util.Log.e("PlainSight","Photo learning failure",e);
                ui.post(()->failLearning("Photo capture failed: "+e.getMessage()));
            }
        });
    }
    private void failLearning(String reason){
        learning=false;learnButton.setEnabled(true);learnCount.setText(reason);state(reason);
    }
    private void hideKeyboard(View v){
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(v.getWindowToken(),0);
        v.clearFocus();
    }
    private void reset(){
        token++;searching=false;overlay.clear();if(focusLock!=null)focusLock.clear();if(zoom!=null)zoom.setVisibility(View.GONE);
        if(searchButton!=null){searchButton.setText("FIND");searchButton.setEnabled(true);}
    }
    private String normalize(String s){
        return s.trim().replaceFirst("(?i)^(please\\s+)?(can you\\s+)?(find|locate|where is|where are|look for|show me)\\s+","")
            .replaceFirst("(?i)^(my|the|a|an)\\s+","").trim();
    }
    private void search(){
        String requested=normalize(query.getText().toString());
        if(requested.isEmpty()){query.setError("What should I find?");return;}
        if(learning){state("Finish recording the item first.");return;}
        closeLearn();reset();hideKeyboard(query);
        final int job=token;
        misses=0;searching=true;
        searchButton.setText("SCAN");searchButton.setEnabled(true);
        String digit=OfflineTextFinder.searchTerm(requested);
        if(digit!=null){
            state("Offline text search · "+digit);
            scanText(job,digit);
            return;
        }
        if(localObjects==null){
            state("On-device detector loading. Check again in a moment.");
            searching=false;return;
        }
        String personal=prefs.getString(savedKey(requested),null);
        if(personal!=null && personalMatcher!=null){
            state("Comparing the camera with your "+requested+" reference photos…");
            scanPersonal(job,requested,personal);return;
        }
        if(!localObjects.supports(requested)){
            searching=false;
            state("No offline class for '"+requested+"' yet. Try straw, keys, cup or a number.");
            return;
        }
        state("On-device object scan · "+requested);
        scanObject(job,requested);
    }
    private void holdMatch(Bitmap snap,RectF bounds,String message) {
        if(focusLock==null||bounds==null||snap==null){if(snap!=null)snap.recycle();return;}
        searching=false;
        focusLock.hold(snap,bounds);
        overlay.clear();
        searchButton.setEnabled(true);
        searchButton.setText("AGAIN");
        state(message+" · locked zoom. Tap AGAIN to scan live.");
    }
    private void scanText(int job,String value){
        if(!searching||job!=token)return;
        Bitmap current=frame();
        if(current==null||current.getWidth()<50){
            ui.postDelayed(()->scanText(job,value),250);return;
        }
        localOcr.scan(current,value,new OfflineTextFinder.Callback(){
            @Override public void onResult(RectF box){
                if(job!=token||!searching){current.recycle();return;}
                if(box!=null){
                    // Retain the exact image that OCR inspected. Its box cannot drift.
                    holdMatch(current,box,"FOUND text '"+value+"'");
                }else {
                    current.recycle();
                    if(++misses<14)ui.postDelayed(()->scanText(job,value),300);
                    else{searching=false;state("Couldn't read '"+value+"' · move closer.");}
                }
            }
            @Override public void onError(String error){
                current.recycle();if(job!=token)return;
                searching=false;state("Offline text error: "+error);
            }
        });
    }
    private void scanObject(int job,String requested){
        if(!searching||job!=token)return;
        Bitmap current=frame();
        if(current==null||current.getWidth()<50){
            ui.postDelayed(()->scanObject(job,requested),250);return;
        }
        worker.execute(()->{
            try{
                final long started=android.os.SystemClock.elapsedRealtime();
                OfflineObjectFinder.Match match=localObjects.find(current,requested);
                final long ms=android.os.SystemClock.elapsedRealtime()-started;
                ui.post(()->{
                    if(job!=token||!searching){current.recycle();return;}
                    if(match!=null){
                        holdMatch(current,match.bounds,"POSSIBLE "+match.label+
                            " ("+Math.round(match.score*100)+"% model score, "+ms+" ms)");
                    }else{
                        current.recycle();
                        if(++misses<5){
                            state("Scanning "+requested+" · "+ms+" ms/frame");
                            ui.postDelayed(()->scanObject(job,requested),160);
                        }else{
                            searching=false;
                            state("No "+requested+" detected. Move closer and tap FIND again.");
                        }
                    }
                });
            }catch(Throwable e){
                current.recycle();
                android.util.Log.e("PlainSight","On-device detector error",e);
                ui.post(()->{if(job!=token)return;searching=false;
                    state("Local detector error: "+e.getClass().getSimpleName()+" "+e.getMessage());});
            }
        });
    }
    private void scanPersonal(int job,String requested,String saved){
        if(!searching||job!=token)return;
        Bitmap current=frame();
        if(current==null||current.getWidth()<50){
            ui.postDelayed(()->scanPersonal(job,requested,saved),260);return;
        }
        worker.execute(()->{
            try{
                long started=android.os.SystemClock.elapsedRealtime();
                PersonalMatcher.Match hit=personalMatcher.find(current,new JSONArray(saved));
                long ms=android.os.SystemClock.elapsedRealtime()-started;
                ui.post(()->{
                    if(job!=token||!searching){current.recycle();return;}
                    if(hit!=null){
                        holdMatch(current,hit.bounds,"POSSIBLE saved "+requested+
                            " · "+hit.inliers+" feature matches · "+ms+" ms");
                    } else{
                        current.recycle();
                        if(++misses<4){
                            state("Checking saved "+requested+" from another angle · "+ms+" ms");
                            ui.postDelayed(()->scanPersonal(job,requested,saved),190);
                        } else if(localObjects!=null&&localObjects.supports(requested)){
                            misses=0;state("Saved-item match uncertain · trying generic "+requested+" detector");
                            scanObject(job,requested);
                        } else {
                            searching=false;
                            state("Couldn't verify saved "+requested+". Try filling the learning guide.");
                        }
                    }
                });
            }catch(Throwable e){
                current.recycle();
                android.util.Log.e("PlainSight","Personal image matching error",e);
                ui.post(()->{if(job==token){searching=false;state("Saved-item error: "+e.getMessage());}});
            }
        });
    }
    private void setMagnifier(Bitmap scene,GeminiLocator.Result r){
        try{
            int w=scene.getWidth(),h=scene.getHeight();
            int x=Math.max(0,Math.round(r.left*w)),y=Math.max(0,Math.round(r.top*h));
            int x2=Math.min(w,Math.round(r.right*w)),y2=Math.min(h,Math.round(r.bottom*h));
            if(x2-x<4||y2-y<4)return;
            Bitmap cropped=Bitmap.createBitmap(scene,x,y,x2-x,y2-y);
            zoom.setImageBitmap(cropped);zoom.setVisibility(View.VISIBLE);
        }catch(Exception e){android.util.Log.w("PlainSight","Magnifier failed",e);}
    }
    private void listen(){
        try{
            Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault());
            i.putExtra(RecognizerIntent.EXTRA_PROMPT,"What are you looking for?");
            startActivityForResult(i,REQ_VOICE);
        }catch(Exception e){state("Voice recognition unavailable. Type your target.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==REQ_VOICE&&result==Activity.RESULT_OK&&data!=null){
            ArrayList<String> words=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(words!=null&&!words.isEmpty()){query.setText(words.get(0));ui.postDelayed(this::search,850);}
        }
    }
    @Override public void onBackPressed(){
        if(keyPanel.getVisibility()==View.VISIBLE){keyPanel.setVisibility(View.GONE);return;}
        if(teachPanel.getVisibility()==View.VISIBLE){closeLearn();return;}
        reset();state("Ready.");
    }
    @Override protected void onPause(){reset();super.onPause();}
    @Override protected void onDestroy(){
        reset();worker.shutdownNow();
        if(localOcr!=null)localOcr.close();
        if(localObjects!=null)try{localObjects.close();}catch(Exception ignored){}
        super.onDestroy();
    }
    private final class Overlay extends View {
        private final Paint paint=new Paint(3);
        private RectF box;
        boolean teach=false;
        Overlay(){super(MainActivity.this);}
        void clear(){box=null;invalidate();}
        void show(float x0,float y0,float x1,float y1){
            box=new RectF(x0*getWidth(),y0*getHeight(),x1*getWidth(),y1*getHeight());
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            paint.setColor(CYAN);paint.setStrokeWidth(dp(3));paint.setStyle(Paint.Style.STROKE);
            if(teach){
                float side=getWidth()*.56f,cx=getWidth()/2f,cy=getHeight()*.5f;
                canvas.drawRoundRect(new RectF(cx-side/2,cy-side/2,cx+side/2,cy+side/2),dp(18),dp(18),paint);
            }else if(box!=null){
                canvas.drawRoundRect(box,dp(11),dp(11),paint);
                paint.setStrokeWidth(dp(1));
                canvas.drawCircle(box.centerX(),box.centerY(),dp(9),paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
