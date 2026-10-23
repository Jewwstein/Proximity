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
    boolean game=false,edit=false,showButtons=true,mouseHeld=false,useXInput=false,pinching=false;
    float lastPinchDistance=0,lastPinchCenterX=0,lastPinchCenterY=0;
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
        buttons.add(new KeyButton("JOY","JOY",.13f,.75f,.17f));
        buttons.add(new KeyButton("AIM","AIM",.61f,.77f,.13f));
        buttons.add(new KeyButton("A","SPACE",.88f,.77f,.072f));
        buttons.add(new KeyButton("B","E",.79f,.87f,.072f));
        buttons.add(new KeyButton("X","R",.79f,.67f,.072f));
        buttons.add(new KeyButton("Y","F",.70f,.77f,.072f));
        buttons.add(new KeyButton("LB","Q",.20f,.20f,.073f));
        buttons.add(new KeyButton("RB","SHIFT",.80f,.20f,.073f));
        buttons.add(new KeyButton("LT","CTRL",.09f,.19f,.073f));
        buttons.add(new KeyButton("RT","MOUSE_LEFT",.92f,.19f,.073f));
        buttons.add(new KeyButton("ESC","ESC",.50f,.13f,.062f));
        buttons.add(new KeyButton("START","ENTER",.56f,.18f,.053f));
        buttons.add(new KeyButton("BACK","TAB",.43f,.18f,.053f));
        buttons.add(new KeyButton("L3","SHIFT",.28f,.85f,.045f));
        buttons.add(new KeyButton("R3","CTRL",.66f,.90f,.045f));
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
    void pressButton(KeyButton b){
        if(useXInput)send.send("padDown",b.label,cursorX,cursorY);
        else press(b.key);
    }
    void releaseButton(KeyButton b){
        if(useXInput)send.send("padUp",b.label,cursorX,cursorY);
        else release(b.key);
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
        for(KeyButton b:fingers.values())if(!b.key.equals("JOY")&&!b.key.equals("AIM"))releaseButton(b);
        fingers.clear();for(String key:joystickDown)release(key);joystickDown.clear();
        if(mouseHeld){send.send("up","",cursorX,cursorY);mouseHeld=false;}
        send.send("padReset","",cursorX,cursorY);
        send.send("releaseAllKeys","",cursorX,cursorY);
        if(holdTask!=null)ui.removeCallbacks(holdTask);
        mousePointer=-1;editingPointer=-1;pinching=false;
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        // Software cursor is essential because Desktop Duplication may not include
        // Windows' separate hardware mouse pointer in the H.264 texture.
        if(!game){
            float px=getWidth()*.5f+(cursorX-.5f)*videoBoxW;
            float py=getHeight()*.5f+(cursorY-.5f)*videoBoxH;
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(3,Math.min(getWidth(),getHeight())*.004f));
            p.setColor(0xFF000000);c.drawCircle(px,py,11,p);
            p.setColor(0xFFFFFFFF);c.drawCircle(px,py,8,p);
            c.drawLine(px-16,py,px+16,py,p);c.drawLine(px,py-16,px,py+16,p);
            p.setStyle(Paint.Style.FILL);
        }
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
            c.drawText(b.label.equals("JOY")?"MOVE":b.label.equals("AIM")?"LOOK":b.label,cx,cy+shortSide*.010f,p);
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
        if(useXInput){axis(x,y,b,"LEFT");return;}
        float dx=(x/getWidth()-b.x),dy=(y/getHeight()-b.y);
        float threshold=b.r*.32f;
        HashSet<String> desired=new HashSet<>();
        if(dx < -threshold)desired.add("A");else if(dx>threshold)desired.add("D");
        if(dy < -threshold)desired.add("W");else if(dy>threshold)desired.add("S");
        for(String old:new HashSet<>(joystickDown))if(!desired.contains(old)){release(old);joystickDown.remove(old);}
        for(String key:desired)if(joystickDown.add(key))press(key);
    }
    void axis(float x,float y,KeyButton b,String stick){
        float radius=Math.max(1f,b.r*Math.min(getWidth(),getHeight()));
        float dx=Math.max(-1f,Math.min(1f,(x-b.x*getWidth())/radius));
        float dy=Math.max(-1f,Math.min(1f,(y-b.y*getHeight())/radius));
        send.send("padAxis",stick,(dx+1)/2f,(dy+1)/2f);
    }
    void aim(float x,float y,KeyButton b){
        if(useXInput)axis(x,y,b,"RIGHT");
        else {float dx=(x-b.x*getWidth())/(b.r*Math.min(getWidth(),getHeight()));
              float dy=(y-b.y*getHeight())/(b.r*Math.min(getWidth(),getHeight()));
              mouseMove(dx*4f,dy*4f);}
    }
    void mouseMove(float dx,float dy){
        float vw=Math.max(1,videoBoxW),vh=Math.max(1,videoBoxH);
        cursorX=Math.max(0,Math.min(1,cursorX+dx/vw));
        cursorY=Math.max(0,Math.min(1,cursorY+dy/vh));
        send.send("move","",cursorX,cursorY);
        invalidate();
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
            if(!game && e.getPointerCount()==2 && action==MotionEvent.ACTION_POINTER_DOWN){
                pinching=true;
                lastPinchDistance=(float)Math.hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));
                lastPinchCenterX=(e.getX(1)+e.getX(0))*.5f;
                lastPinchCenterY=(e.getY(1)+e.getY(0))*.5f;
                if(holdTask!=null)ui.removeCallbacks(holdTask);
                mousePointer=-1;return true;
            }
            KeyButton hit=hit(x,y);
            if(edit&&hit!=null){selected=hit;editingPointer=id;originX=x;originY=y;lastX=x;lastY=y;invalidate();return true;}
            if(hit!=null){
                fingers.put(id,hit);
                if(hit.key.equals("JOY"))joystick(x,y,hit);
                else if(hit.key.equals("AIM"))aim(x,y,hit);
                else pressButton(hit);
                invalidate();return true;
            }
            if(mousePointer<0){
                mousePointer=id;originX=x;originY=y;lastX=x;lastY=y;moved=false;downTime=SystemClock.elapsedRealtime();
                holdTask=()->{
                    if(mousePointer==id&&!moved&&!mouseHeld){mouseHeld=true;send.send("down","",cursorX,cursorY);}
                };
                ui.postDelayed(holdTask,460);
            }
            return true;
          }
          case MotionEvent.ACTION_MOVE:{
            if(pinching&&e.getPointerCount()>=2){
                float distance=(float)Math.hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));
                float cx=(e.getX(0)+e.getX(1))*.5f;
                float cy=(e.getY(0)+e.getY(1))*.5f;
                MainActivity activity=(MainActivity)getContext();
                if(lastPinchDistance>10)activity.onPinch(distance/lastPinchDistance);
                activity.panZoom(cx-lastPinchCenterX,cy-lastPinchCenterY);
                lastPinchDistance=distance;lastPinchCenterX=cx;lastPinchCenterY=cy;
                return true;
            }
            for(int i=0;i<e.getPointerCount();i++){
                int pid=e.getPointerId(i);float px=e.getX(i),py=e.getY(i);
                if(pid==editingPointer&&selected!=null){
                    selected.x=Math.max(.03f,Math.min(.97f,px/getWidth()));
                    selected.y=Math.max(.04f,Math.min(.96f,py/getHeight()));
                    invalidate();
                }else if(fingers.containsKey(pid)){
                    KeyButton b=fingers.get(pid);if(b.key.equals("JOY"))joystick(px,py,b);
                    else if(b.key.equals("AIM"))aim(px,py,b);
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
            if(pinching){if(e.getPointerCount()<=2){pinching=false;mousePointer=-1;}
                return true;}
            if(id==editingPointer){
                editingPointer=-1;save();invalidate();
                if(Math.abs(x-originX)+Math.abs(y-originY)<14&&selected!=null&&!selected.key.equals("JOY"))mapper.edit(selected.label,selected.key);
            }
            KeyButton b=fingers.remove(id);
            if(b!=null){if(b.key.equals("JOY")){
                    if(useXInput)send.send("padAxis","LEFT",.5f,.5f);
                    for(String key:joystickDown)release(key);joystickDown.clear();
                }else if(b.key.equals("AIM")){
                    if(useXInput)send.send("padAxis","RIGHT",.5f,.5f);
                }else releaseButton(b);invalidate();}
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
