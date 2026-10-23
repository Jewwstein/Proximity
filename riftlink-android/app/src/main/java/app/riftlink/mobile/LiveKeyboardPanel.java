package app.riftlink.mobile;

import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Remote keyboard with two separate paths:
 *  - Gboard committed text -> immediately send Unicode text to Windows
 *  - Holdable buttons -> keyDown/keyUp for Windows games (WASD, Shift etc.)
 *
 * A soft keyboard does not generate reliable hold/release events for game input.
 * The holdable keys below deliberately use ACTION_DOWN/ACTION_UP.
 */
public final class LiveKeyboardPanel extends LinearLayout {
    private final MainActivity activity;
    private final Consumer<String> feedback;
    private final FrameLayout desktopRoot;
    private final EditText editor;
    private final TextView explanation;
    private final LinearLayout gameRows;
    private final HashSet<String> held=new HashSet<>();
    private boolean processing=false;
    private final InputMethodManager ime;
    private boolean shown=false;
    public LiveKeyboardPanel(MainActivity a,FrameLayout root,Consumer<String> feedback){
        super(a);activity=a;desktopRoot=root;this.feedback=feedback;
        ime=(InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE);
        setOrientation(VERTICAL);
        setPadding(px(10),px(8),px(10),px(8));
        setBackgroundColor(0xF0081425);
        setVisibility(GONE);
        setFocusableInTouchMode(true);
        LinearLayout titleBar=new LinearLayout(a);
        titleBar.setOrientation(HORIZONTAL);
        TextView title=new TextView(a);
        title.setText("RiftLink  •  LIVE REMOTE KEYBOARD");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setTextSize(13);
        title.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.addView(title,new LinearLayout.LayoutParams(0,px(38),1));
        Button gameMode=new Button(a);
        gameMode.setText("Game keys");
        style(gameMode);
        titleBar.addView(gameMode,new LinearLayout.LayoutParams(px(110),px(40)));
        Button close=new Button(a);
        close.setText("✕");style(close);
        titleBar.addView(close,new LinearLayout.LayoutParams(px(48),px(40)));
        addView(titleBar);
        editor=new RemoteEditText(a);
        editor.setSingleLine(true);
        editor.setTextSize(16);
        editor.setTextColor(Color.WHITE);
        editor.setHintTextColor(0xFF9FB4CE);
        editor.setHint("Tap here to type live into your PC");
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setImeOptions(EditorInfo.IME_ACTION_NONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        editor.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6EAFE9));
        addView(editor,new LinearLayout.LayoutParams(-1,px(49)));
        editor.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){}
            public void afterTextChanged(Editable s){
                if(processing||s.length()==0)return;
                String committed=s.toString();
                processing=true;
                try{send("text",committed);}
                finally{s.clear();processing=false;}
            }
        });
        explanation=new TextView(a);
        explanation.setText("Text goes to Windows immediately. For movement, HOLD W/A/S/D below.");
        explanation.setTextSize(11);
        explanation.setTextColor(0xFFB8CDEA);
        addView(explanation,new LinearLayout.LayoutParams(-1,px(26)));
        gameRows=new LinearLayout(a);
        gameRows.setOrientation(VERTICAL);
        addView(gameRows);
        gameRows.addView(makeStrip(new String[]{"W","A","S","D","SPACE","SHIFT","CTRL","ALT"}));
        gameRows.addView(makeStrip(new String[]{"UP","LEFT","DOWN","RIGHT","TAB","ESC","ENTER","BACKSPACE"}));
        gameRows.setVisibility(VISIBLE);
        gameMode.setOnClickListener(v->{
            boolean show=gameRows.getVisibility()!=VISIBLE;
            gameRows.setVisibility(show?VISIBLE:GONE);
            gameMode.setText(show?"Hide game keys":"Game keys");
            if(show){ime.hideSoftInputFromWindow(editor.getWindowToken(),0);}
            else {editor.requestFocus();ime.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);}
        });
        close.setOnClickListener(v->hide());
    }
    private int px(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private void style(Button b){
        b.setTextColor(Color.WHITE);b.setTextSize(11);b.setAllCaps(false);
        b.setPadding(0,0,0,0);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF174774));
    }
    private HorizontalScrollView makeStrip(String[] names){
        HorizontalScrollView scroller=new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(true);
        scroller.setFillViewport(true);
        LinearLayout row=new LinearLayout(activity);
        row.setOrientation(HORIZONTAL);
        for(String key:names){
            Button b=new Button(activity);style(b);
            b.setText(key.equals("BACKSPACE")?"⌫":key);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(px(key.length()>4?83:59),px(47));
            lp.setMargins(px(3),px(2),px(3),px(2));
            row.addView(b,lp);
            b.setOnTouchListener((view,e)->{
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                    hold(key);return true;
                }
                if(e.getActionMasked()==MotionEvent.ACTION_UP
                        ||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                    release(key);return true;
                }
                return true;
            });
        }
        scroller.addView(row,new HorizontalScrollView.LayoutParams(-2,-2));
        return scroller;
    }
    private void send(String action,String value){
        RiftNet net=activity.net;
        if(net!=null)net.input(action,value,.5f,.5f);
        else feedback.accept("Connect RiftLink to your PC first");
    }
    private String remoteKey(String key){return key.equals("BACKSPACE")?"BACKSPACE":key;}
    public void hold(String key){
        if(held.add(key)){
            if(key.equals("BACKSPACE")){
                send("backspace","");
            }else send("keyDown",remoteKey(key));
        }
    }
    public void release(String key){
        if(held.remove(key)&&!key.equals("BACKSPACE"))
            send("keyUp",remoteKey(key));
    }
    public void releaseAll(){
        for(String key:new HashSet<>(held))release(key);
        held.clear();
    }
    public void show(){
        if(shown)return;
        shown=true;
        setVisibility(VISIBLE);
        editor.requestFocus();
        postDelayed(()->{
            if(shown)ime.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);
        },140);
    }
    public void hide(){
        releaseAll();shown=false;
        if(editor!=null)ime.hideSoftInputFromWindow(editor.getWindowToken(),0);
        setVisibility(GONE);
        desktopRoot.requestFocus();
    }
    public boolean isShowing(){return shown;}
    @Override protected void onDetachedFromWindow(){releaseAll();super.onDetachedFromWindow();}

    private class RemoteEditText extends androidxUnusedEditText {
        RemoteEditText(Context ctx){super(ctx);}
        @Override public InputConnection onCreateInputConnection(EditorInfo attr){
            InputConnection base=super.onCreateInputConnection(attr);
            return new InputConnectionWrapper(base,true){
                @Override public boolean deleteSurroundingText(int before,int after){
                    // Soft keyboards often use deleteSurroundingText rather than KeyEvent DEL.
                    for(int i=0;i<Math.min(25,before);i++)send("backspace","");
                    return true;
                }
                @Override public boolean sendKeyEvent(KeyEvent event){
                    if(event.getKeyCode()==KeyEvent.KEYCODE_DEL){
                        if(event.getAction()==KeyEvent.ACTION_DOWN)send("backspace","");
                        return true;
                    }
                    if(event.getKeyCode()==KeyEvent.KEYCODE_ENTER){
                        if(event.getAction()==KeyEvent.ACTION_DOWN)send("enter","");
                        return true;
                    }
                    return super.sendKeyEvent(event);
                }
                @Override public boolean performEditorAction(int actionCode){
                    send("enter","");return true;
                }
            };
        }
        @Override public boolean onKeyDown(int code,KeyEvent e){
            if(code==KeyEvent.KEYCODE_DEL){send("backspace","");return true;}
            if(code==KeyEvent.KEYCODE_ENTER){send("enter","");return true;}
            return super.onKeyDown(code,e);
        }
    }
    // Deliberately a plain Android EditText; no external libraries.
    private static class androidxUnusedEditText extends EditText {
        androidxUnusedEditText(Context context){super(context);}
    }
}
