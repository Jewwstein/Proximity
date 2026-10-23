package app.riftlink.mobile;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import org.json.*;
import java.util.*;

public final class ControlOverlay extends View {
    interface Sender{void send(String action,String key,float x,float y);}
    interface Mapper{void edit(String name,String current);}
    final Sender send; final Mapper mapper;final android.content.SharedPreferences prefs;
    static final class KeyButton {
        String label,key; float x,y,r;
        KeyButton(String l,String k,float a,float b,float c){label=l;key=k;x=a;y=b;r=c;}
    }
    final ArrayList<KeyButton> buttons=new ArrayList<>();
    final HashMap<Integer,KeyButton> fingers=new HashMap<>();
    final HashSet<String> joystickDown=new HashSet<>();
    final Paint p=new Paint(3);
    boolean game=false,edit=false,showButtons=true,mouseHeld=false;
    int opacity=135;
    float cursorX=.5f,cursorY=.5f,videoBoxW,videoBoxH;
    int mousePointer=-1,editingPointer=-1;
    KeyButton selected=null;
    float originX,originY,lastX,lastY;
    long downTime;boolean moved=false;
    final Handler ui=new Handler(Looper.getMainLooper());
    Runnable holdTask;
    ControlOverlay(Context c,Sender s,Mapper m){
        super(c);send=s;mapper=m;prefs=c.getSharedPreferences("controller",0);
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        game=prefs.getBoolean("game",false);
        opacity=prefs.getInt("opacity",135);
        showButtons=prefs.getBoolean("show",true);
        defaults();restore();
    }
    void defaults(){
        buttons.clear();
        buttons.add(new KeyButton("JOY","JOY",.12f,.73f,.16f));
        buttons.add(new KeyButton("A","SPACE",.88f,.77f,.072f));
        buttons.add(new KeyButton("B","E",.79f,.87f,.072f));
        buttons.add(new KeyButton("X","R",.79f,.67f,.072f));
        buttons.add(new KeyButton("Y","F",.70f,.77f,.072f));
        buttons.add(new KeyButton("LB","Q",.20f,.20f,.073f));
        buttons.add(new KeyButton("RB","SHIFT",.80f,.20f,.073f));
        buttons.add(new KeyButton("LT","CTRL",.09f,.19f,.073f));
        buttons.add(new KeyButton("RT","MOUSE_LEFT",.92f,.19f,.073f));
        buttons.add(new KeyButton("ESC","ESC",.50f,.13f,.062f));
    }
    void restore(){
        try{
            JSONArray ar=new JSONArray(prefs.getString("buttons","[]"));
            for(int i=0;i<ar.length();i++){
                JSONObject o=ar.getJSONObject(i);
                String label=o.optString("label");
                for(KeyButton b:buttons)if(b.label.equals(label)){
                    b.key=o.optString("key",b.key);
                    b.x=(float)o.optDouble("x",b.x);b.y=(float)o.optDouble("y",b.y);
                    b.r=(float)o.optDouble("r",b.r);
                }
            }
        }catch(Exception ignored){}
    }
    void save(){
        JSONArray ar=new JSONArray();
        for(KeyButton b:buttons){
            JSONObject o=new JSONObject();
            try{o.put("label",b.label);o.put("key",b.key);o.put("x",b.x);o.put("y",b.y);o.put("r",b.r);}catch(Exception ignored){}
            ar.put(o);
        }
        prefs.edit().putString("buttons",ar.toString()).putInt("opacity",opacity)
            .putBoolean("game",game).putBoolean("show",showButtons).apply();
    }
    void reset(){cancelAll();}
    void resizeSelected(float factor){if(selected!=null){selected.r=Math.max(.03f,Math.min(.23f,selected.r*factor));save();invalidate();}}
    void setMapping(String name,String key){
        for(KeyButton b:buttons)if(b.label.equals(name)){b.key=key;save();invalidate();return;}
    }
    void release(String key){
        if(key.equals("MOUSE_LEFT"))send.send("up","",cursorX,cursorY);
        else send.send("keyUp",key,cursorX,cursorY);
    }
    void press(String key){
        if(key.equals("MOUSE_LEFT"))send.send("down","",cursorX,cursorY);
        else send.send("keyDown",key,cursorX,cursorY);
    }
    void cancelAll(){
        for(KeyButton b:fingers.values())if(!b.key.equals("JOY"))release(b.key);
        fingers.clear();for(String key:joystickDown)release(key);joystickDown.clear();
        if(mouseHeld){send.send("up","",cursorX,cursorY);mouseHeld=false;}
        if(holdTask!=null)ui.removeCallbacks(holdTask);
        mousePointer=-1;editingPointer=-1;
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        if(!game||!showButtons)return;
        float shortSide=Math.min(getWidth(),getHeight());
        for(KeyButton b:buttons){
            float cx=b.x*getWidth(),cy=b.y*getHeight(),r=b.r*shortSide;
            boolean active=fingers.containsValue(b);
            p.setColor(Color.argb(opacity,active?62:20,active?156:79,active?237:147));
            p.setStyle(Paint.Style.FILL);c.drawCircle(cx,cy,r,p);
            p.setColor(selected==b&&edit?Color.YELLOW:0xFFA7DDFF);p.setStrokeWidth(Math.max(2,shortSide*.003f));
            p.setStyle(Paint.Style.STROKE);c.drawCircle(cx,cy,r,p);
            p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(Math.max(12,shortSide*(b.label.equals("JOY")?.035f:.03f)));
            c.drawText(b.label.equals("JOY")?"MOVE":b.label,cx,cy+shortSide*.010f,p);
            if(edit&&b==selected){
                p.setTextSize(shortSide*.020f);c.drawText(b.key,cx,cy+r+shortSide*.025f,p);
            }
        }
    }
    KeyButton hit(float x,float y){
        if(!game||!showButtons)return null;
        float shortSide=Math.min(getWidth(),getHeight());
        for(int i=buttons.size()-1;i>=0;i--){
            KeyButton b=buttons.get(i);float dx=x-b.x*getWidth(),dy=y-b.y*getHeight();
            if(dx*dx+dy*dy < b.r*b.r*shortSide*shortSide*1.5f)return b;
        }return null;
    }
    void joystick(float x,float y,KeyButton b){
        float dx=(x/getWidth()-b.x),dy=(y/getHeight()-b.y);
        float threshold=b.r*.32f;
        HashSet<String> desired=new HashSet<>();
        if(dx < -threshold)desired.add("A");else if(dx>threshold)desired.add("D");
        if(dy < -threshold)desired.add("W");else if(dy>threshold)desired.add("S");
        for(String old:new HashSet<>(joystickDown))if(!desired.contains(old)){release(old);joystickDown.remove(old);}
        for(String key:desired)if(joystickDown.add(key))press(key);
    }
    void mouseMove(float dx,float dy){
        float vw=Math.max(1,videoBoxW),vh=Math.max(1,videoBoxH);
        cursorX=Math.max(0,Math.min(1,cursorX+dx/vw));
        cursorY=Math.max(0,Math.min(1,cursorY+dy/vh));
        send.send("move","",cursorX,cursorY);
    }
    void mouseClick(){
        send.send("down","",cursorX,cursorY);
        ui.postDelayed(()->send.send("up","",cursorX,cursorY),75);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        int action=e.getActionMasked(),index=e.getActionIndex(),id=e.getPointerId(index);
        float x=e.getX(index),y=e.getY(index);
        switch(action){
          case MotionEvent.ACTION_DOWN:
          case MotionEvent.ACTION_POINTER_DOWN: {
            KeyButton hit=hit(x,y);
            if(edit&&hit!=null){selected=hit;editingPointer=id;originX=x;originY=y;lastX=x;lastY=y;invalidate();return true;}
            if(hit!=null){
                fingers.put(id,hit);
                if(hit.key.equals("JOY"))joystick(x,y,hit);else press(hit.key);
                invalidate();return true;
            }
            if(mousePointer<0){
                mousePointer=id;originX=x;originY=y;lastX=x;lastY=y;moved=false;downTime=SystemClock.elapsedRealtime();
                holdTask=()->{
                    if(mousePointer==id&&!moved&&!mouseHeld){mouseHeld=true;send.send("down","",cursorX,cursorY);}
                };
                ui.postDelayed(holdTask,460);
            }else if(!game&&e.getPointerCount()==2){send.send("right","",cursorX,cursorY);}
            return true;
          }
          case MotionEvent.ACTION_MOVE:{
            for(int i=0;i<e.getPointerCount();i++){
                int pid=e.getPointerId(i);float px=e.getX(i),py=e.getY(i);
                if(pid==editingPointer&&selected!=null){
                    selected.x=Math.max(.03f,Math.min(.97f,px/getWidth()));
                    selected.y=Math.max(.04f,Math.min(.96f,py/getHeight()));
                    invalidate();
                }else if(fingers.containsKey(pid)){
                    KeyButton b=fingers.get(pid);if(b.key.equals("JOY"))joystick(px,py,b);
                }else if(mousePointer==pid){
                    float dx=px-lastX,dy=py-lastY;
                    if(Math.abs(px-originX)+Math.abs(py-originY)>14){moved=true;if(holdTask!=null)ui.removeCallbacks(holdTask);}
                    mouseMove(dx,dy);lastX=px;lastY=py;
                }
            }return true;
          }
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_POINTER_UP:
          case MotionEvent.ACTION_CANCEL:{
            if(id==editingPointer){
                editingPointer=-1;save();invalidate();
                if(Math.abs(x-originX)+Math.abs(y-originY)<14&&selected!=null&&!selected.key.equals("JOY"))mapper.edit(selected.label,selected.key);
            }
            KeyButton b=fingers.remove(id);
            if(b!=null){if(b.key.equals("JOY")){for(String key:joystickDown)release(key);joystickDown.clear();}
                else release(b.key);invalidate();}
            if(id==mousePointer){
                mousePointer=-1;
                if(holdTask!=null)ui.removeCallbacks(holdTask);
                if(mouseHeld){mouseHeld=false;send.send("up","",cursorX,cursorY);}
                else if(!moved&&!edit)mouseClick();
            }
            if(action==MotionEvent.ACTION_CANCEL)cancelAll();
            return true;
          }
          default:return true;
        }
    }
}
