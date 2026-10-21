package com.plainsight.finder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private static final int REQ_CAMERA=10,REQ_VOICE=11;
    private static final int CYAN=0xff86e8fc,BG=0xff08121f;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private PreviewView camera;
    private FrameLayout root;
    private TextView message,learnStatus;
    private EditText query,itemName;
    private Button learnButton,findButton;
    private LinearLayout teachPanel,savedList;
    private Overlay overlay;
    private VisualMatcher matcher;
    private com.google.mlkit.vision.label.ImageLabeler labels;
    private boolean searchActive=false,learning=false,ready=false;
    private int token=0,viewsSaved=0;
    private String target="";
    private final ArrayList<float[]> samples=new ArrayList<>();

    private int dp(float x){return Math.round(getResources().getDisplayMetrics().density*x);}
    private GradientDrawable rounded(int color,int radius){
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;
    }
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private Button btn(String value,int background,int ink){
        Button b=new Button(this);b.setText(value);b.setAllCaps(false);
        b.setTextSize(14);b.setTextColor(ink);b.setBackground(rounded(background,14));return b;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            |View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_FULLSCREEN);
        makeScreen();
        worker.execute(()->{
            try{
                matcher=new VisualMatcher(this);
                labels=ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS);
                ready=true;show("Offline AI ready. Point, speak, or teach a specific item.");
            }catch(Throwable e){
                android.util.Log.e("PlainSight","Model startup error",e);
                show("AI model failed: "+String.valueOf(e.getMessage()));
            }
        });
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);
        else connectCamera();
    }
    private void makeScreen(){
        root=new FrameLayout(this);root.setBackgroundColor(BG);setContentView(root);
        camera=new PreviewView(this);
        camera.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        camera.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(camera,new FrameLayout.LayoutParams(-1,-1));
        overlay=new Overlay();
        root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(20),dp(20),dp(15),dp(10));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xed07111f,0xa807111f,0x0007111f}));
        root.addView(top,new FrameLayout.LayoutParams(-1,dp(150),Gravity.TOP));
        top.addView(text("PLAIN SIGHT",21,CYAN,true),new LinearLayout.LayoutParams(-1,dp(35)));
        message=text("Starting camera and AI…",13,Color.WHITE,false);
        message.setMaxLines(4);
        top.addView(message,new LinearLayout.LayoutParams(-1,dp(82)));

        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(10),dp(14),dp(10),dp(25));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xff07111f,0xe707111f,0x0007111f}));
        root.addView(bottom,new FrameLayout.LayoutParams(-1,dp(128),Gravity.BOTTOM));
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(7),dp(5),dp(7),dp(5));bar.setBackground(rounded(0xee102537,22));
        bottom.addView(bar,new LinearLayout.LayoutParams(-1,dp(67)));
        Button add=btn("+",0xff23485d,CYAN);add.setTextSize(24);
        bar.addView(add,new LinearLayout.LayoutParams(dp(45),dp(51)));
        add.setOnClickListener(v->openTeach());
        query=new EditText(this);query.setSingleLine(true);
        query.setTextColor(Color.WHITE);query.setHintTextColor(0xffb3c5cf);
        query.setTextSize(15);query.setHint("Find my keys");query.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(0,dp(53),1);q.leftMargin=dp(6);
        bar.addView(query,q);
        query.setImeOptions(3);query.setOnEditorActionListener((v,a,e)->{find();return true;});
        Button mic=btn("🎙",0xff23485d,Color.WHITE);mic.setTextSize(19);
        bar.addView(mic,new LinearLayout.LayoutParams(dp(45),dp(51)));
        mic.setOnClickListener(v->voice());
        findButton=btn("FIND",CYAN,BG);
        LinearLayout.LayoutParams f=new LinearLayout.LayoutParams(dp(68),dp(51));f.leftMargin=dp(5);
        bar.addView(findButton,f);findButton.setOnClickListener(v->find());
        buildTeach();
    }
    private void buildTeach(){
        teachPanel=new LinearLayout(this);teachPanel.setOrientation(LinearLayout.VERTICAL);
        teachPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        teachPanel.setPadding(dp(19),dp(33),dp(19),dp(23));
        teachPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xfa07111f,0xe007111f,0x6607111f,0xd907111f,0xf907111f}));
        root.addView(teachPanel,new FrameLayout.LayoutParams(-1,-1));
        teachPanel.setVisibility(View.GONE);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text("TEACH PLAIN SIGHT",20,CYAN,true),new LinearLayout.LayoutParams(0,dp(49),1));
        Button exit=btn("✕",0xff244056,Color.WHITE);
        row.addView(exit,new LinearLayout.LayoutParams(dp(48),dp(48)));
        exit.setOnClickListener(v->closeTeach());
        teachPanel.addView(row,new LinearLayout.LayoutParams(-1,dp(55)));

        itemName=new EditText(this);itemName.setSingleLine(true);itemName.setTextSize(17);
        itemName.setTextColor(Color.WHITE);itemName.setHintTextColor(0xffbacddd);
        itemName.setHint("Name it: car keys");itemName.setPadding(dp(13),0,dp(13),0);
        itemName.setBackground(rounded(0xff183347,14));
        LinearLayout.LayoutParams n=new LinearLayout.LayoutParams(-1,dp(50));n.topMargin=dp(18);
        teachPanel.addView(itemName,n);
        TextView guide=text("Hold the item centered. Tap LEARN, then rotate slowly for eight camera views.",14,Color.WHITE,false);
        guide.setGravity(Gravity.CENTER);teachPanel.addView(guide,new LinearLayout.LayoutParams(-1,dp(94)));
        teachPanel.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));

        learnStatus=text("Hold your item in the blue square",14,CYAN,true);
        learnStatus.setGravity(Gravity.CENTER);
        teachPanel.addView(learnStatus,new LinearLayout.LayoutParams(-1,dp(39)));
        learnButton=btn("LEARN 360° VIEWS",CYAN,BG);
        teachPanel.addView(learnButton,new LinearLayout.LayoutParams(-1,dp(54)));
        learnButton.setOnClickListener(v->startLearning());
        TextView label=text("YOUR SAVED ITEMS",12,0xffc6d8df,true);label.setGravity(Gravity.CENTER);
        teachPanel.addView(label,new LinearLayout.LayoutParams(-1,dp(38)));
        savedList=new LinearLayout(this);savedList.setOrientation(LinearLayout.VERTICAL);
        teachPanel.addView(savedList,new LinearLayout.LayoutParams(-1,dp(105)));
    }
    private void openTeach(){
        reset();itemName.setText("");learnStatus.setText("Hold your item in the blue square");
        overlay.teach=true;overlay.invalidate();teachPanel.setVisibility(View.VISIBLE);refreshSaved();
    }
    private void closeTeach(){
        if(learning)return;
        overlay.teach=false;overlay.invalidate();teachPanel.setVisibility(View.GONE);
        show("Ready · point and find.");
    }
    private void refreshSaved(){
        savedList.removeAllViews();
        if(matcher==null)return;
        for(VisualMatcher.Memory m:matcher.list()){
            LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);
            TextView label=text(m.name+" · "+m.angles.size()+" views",14,Color.WHITE,false);
            line.addView(label,new LinearLayout.LayoutParams(0,dp(37),1));
            Button go=btn("FIND",0xff23485d,CYAN);
            line.addView(go,new LinearLayout.LayoutParams(dp(64),dp(34)));
            go.setOnClickListener(v->{query.setText(m.name);closeTeach();find();});
            Button remove=btn("×",0xff563442,0xffffbfbb);
            LinearLayout.LayoutParams d=new LinearLayout.LayoutParams(dp(35),dp(34));d.leftMargin=dp(7);
            line.addView(remove,d);remove.setOnClickListener(v->{
                worker.execute(()->{matcher.forget(m.name);handler.post(this::refreshSaved);});
            });
            savedList.addView(line);
        }
        if(savedList.getChildCount()==0)
            savedList.addView(text("No saved items yet",12,0xffd0dfed,false));
    }
    private void show(String s){handler.post(()->{if(message!=null)message.setText(s);});}
    private void connectCamera(){
        ListenableFuture<ProcessCameraProvider> cameraProvider=ProcessCameraProvider.getInstance(this);
        cameraProvider.addListener(()->{
            try{
                ProcessCameraProvider provider=cameraProvider.get();provider.unbindAll();
                Preview pipeline=new Preview.Builder().build();
                pipeline.setSurfaceProvider(camera.getSurfaceProvider());
                provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,pipeline);
                show("Camera ready. Tap + to teach your keys, or type to scan.");
            }catch(Exception e){show("Camera error: "+e.getMessage());}
        },ContextCompat.getMainExecutor(this));
    }
    @Override public void onRequestPermissionsResult(int c,String[] permissions,int[] results){
        super.onRequestPermissionsResult(c,permissions,results);
        if(c==REQ_CAMERA){
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)connectCamera();
            else show("Camera permission is required. Enable it in Android Settings.");
        }
    }
    private Bitmap frame(){
        Bitmap b=camera.getBitmap();
        if(b==null||b.getWidth()<100||b.getHeight()<100)return null;
        int newW=Math.min(512,b.getWidth());
        Bitmap smaller=Bitmap.createScaledBitmap(b,newW,
            Math.round((float)b.getHeight()*newW/b.getWidth()),true);
        if(smaller!=b)b.recycle();
        return smaller;
    }
    private void startLearning(){
        if(learning)return;
        String name=itemName.getText().toString().trim();
        if(name.isEmpty()){itemName.setError("Name your item first");return;}
        if(!ready){learnStatus.setText("AI model is not ready. Check status above.");return;}
        learning=true;viewsSaved=0;samples.clear();learnButton.setEnabled(false);
        learnStatus.setText("Rotate the item slowly…");
        InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(itemName.getWindowToken(),0);
        handler.postDelayed(()->captureAngle(name),550);
    }
    private void captureAngle(String name){
        if(!learning)return;
        Bitmap shot=frame();
        if(shot==null){
            learnStatus.setText("Camera isn't ready. Try again.");
            learning=false;learnButton.setEnabled(true);return;
        }
        worker.execute(()->{
            try{
                float[] feature=matcher.learnFrame(shot);shot.recycle();
                samples.add(feature);int current=++viewsSaved;
                handler.post(()->{
                    learnStatus.setText("Captured "+current+" / 8 views · keep turning");
                    if(current<8)handler.postDelayed(()->captureAngle(name),480);
                    else{
                        worker.execute(()->{
                            matcher.remember(name,new ArrayList<>(samples));
                            handler.post(()->{
                                learning=false;learnButton.setEnabled(true);
                                learnStatus.setText("SAVED: "+name+" · ready to search!");
                                query.setText(name);refreshSaved();
                            });
                        });
                    }
                });
            }catch(Throwable error){
                android.util.Log.e("PlainSight","Teaching error",error);
                shot.recycle();
                handler.post(()->{
                    learning=false;learnButton.setEnabled(true);
                    learnStatus.setText("Error: "+error.getClass().getSimpleName()+" "+error.getMessage());
                    show("Learning failed: "+error.getMessage());
                });
            }
        });
    }
    private void reset(){
        searchActive=false;token++;overlay.clear();
    }
    private void find(){
        String text=VisualMatcher.clean(query.getText().toString());
        if(text.isEmpty()){query.setError("What should I find?");return;}
        if(!ready){show("AI still loading or failed. Check status.");return;}
        if(learning){show("Finish teaching first.");return;}
        closeTeach();reset();
        searchActive=true;target=text;int thisSearch=token;
        InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(query.getWindowToken(),0);
        show("Searching for "+text+"… camera remains live");
        doScan(thisSearch);
    }
    private void doScan(int searchId){
        if(!searchActive||searchId!=token)return;
        Bitmap image=frame();
        if(image==null){
            show("Waiting for live camera frame…");
            handler.postDelayed(()->doScan(searchId),500);return;
        }
        final VisualMatcher.Memory item=matcher.get(target);
        if(item!=null){
            worker.execute(()->{
                try{
                    VisualMatcher.Match hit=matcher.scan(image,item);
                    image.recycle();
                    if(searchId!=token||!searchActive)return;
                    if(hit!=null&&hit.score>.72&&hit.advantage>.025){
                        handler.post(()->{
                            if(searchId!=token)return;
                            searchActive=false;overlay.show(hit.box);
                            show("POSSIBLE MATCH: "+item.name+" · check the highlighted area");
                        });
                    }else{
                        show("Searching "+item.name+" · try moving closer or changing angle");
                        handler.postDelayed(()->doScan(searchId),850);
                    }
                }catch(Throwable e){
                    image.recycle();
                    android.util.Log.e("PlainSight","Search failed",e);
                    handler.post(()->{
                        if(searchId==token){reset();show("AI search error: "+e.getMessage());}
                    });
                }
            });
        }else{
            show("Checking visible objects · no personal scan saved for "+target);
            labels.process(InputImage.fromBitmap(image,0))
                .addOnSuccessListener(found->{
                    if(searchId!=token){image.recycle();return;}
                    boolean seen=false;
                    for(ImageLabel label:found){
                        String name=label.getText().toLowerCase(Locale.ROOT);
                        if((name.contains(target)||target.contains(name))&&label.getConfidence()>.7f)
                            seen=true;
                    }
                    image.recycle();searchActive=false;
                    if(seen)show("Possible "+target+" in the scene. Tap + to teach its exact appearance.");
                    else show("Cannot locate '"+target+"' yet. Use + to teach that exact item.");
                }).addOnFailureListener(error->{
                    image.recycle();searchActive=false;show("Image label error: "+error.getMessage());
                });
        }
    }
    private void voice(){
        try{
            Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault());
            i.putExtra(RecognizerIntent.EXTRA_PROMPT,"What should I find?");
            startActivityForResult(i,REQ_VOICE);
        }catch(Exception e){show("Voice unavailable: type your item instead.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==REQ_VOICE&&result==Activity.RESULT_OK&&data!=null){
            ArrayList<String> words=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(words!=null&&!words.isEmpty()){query.setText(words.get(0));find();}
        }
    }
    @Override public void onBackPressed(){
        if(teachPanel.getVisibility()==View.VISIBLE)closeTeach();
        else{reset();show("Ready. Point and search.");}
    }
    @Override protected void onPause(){reset();super.onPause();}
    @Override protected void onDestroy(){
        reset();worker.shutdownNow();
        if(matcher!=null)matcher.close();if(labels!=null)labels.close();
        super.onDestroy();
    }
    private final class Overlay extends View{
        private final Paint paint=new Paint(3);
        RectF spot=null;boolean teach=false;
        Overlay(){super(MainActivity.this);}
        void clear(){spot=null;invalidate();}
        void show(RectF normalized){
            spot=new RectF(normalized.left*getWidth(),normalized.top*getHeight(),
                           normalized.right*getWidth(),normalized.bottom*getHeight());invalidate();
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3.5f));paint.setColor(CYAN);
            if(teach){
                float side=getWidth()*.59f,cx=getWidth()/2f,cy=getHeight()*.52f;
                canvas.drawRoundRect(new RectF(cx-side/2,cy-side/2,cx+side/2,cy+side/2),dp(19),dp(19),paint);
            }else if(spot!=null)canvas.drawRoundRect(spot,dp(16),dp(16),paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
