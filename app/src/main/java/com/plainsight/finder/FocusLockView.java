package com.plainsight.finder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

// An atomic zoomed snapshot: frame and rectangle share one immutable coordinate
// system, so a camera movement cannot leave a highlight on the wrong object.
public final class FocusLockView extends View {
    private Bitmap photo;
    private RectF bounds;
    private final Paint imagePaint=new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int accent=0xff83eaff;
    private final float density;

    public FocusLockView(Context context){
        super(context);
        density=context.getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
    }
    public void hold(Bitmap shot,RectF normalized) {
        clear();
        photo=shot;
        bounds=new RectF(normalized);
        setVisibility(VISIBLE);
        invalidate();
    }
    public boolean isHeld(){return photo!=null;}
    public void clear(){
        Bitmap old=photo;
        photo=null;bounds=null;
        setVisibility(GONE);
        if(old!=null&&!old.isRecycled())old.recycle();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(photo==null||bounds==null||photo.isRecycled())return;
        canvas.drawColor(0xff070c14);
        final float w=getWidth(),h=getHeight(),imgW=photo.getWidth(),imgH=photo.getHeight();
        final float cx=bounds.centerX()*imgW,cy=bounds.centerY()*imgH;
        final float bw=Math.max(1,bounds.width()*imgW),bh=Math.max(1,bounds.height()*imgH);
        // Center the matched object, not the old fixed pixel coordinates.
        final float zoom=Math.max(1.15f,Math.min(4.5f,
            Math.min(w/(bw*2.25f),h/(bh*2.25f))));
        final float left=w*.5f-cx*zoom,top=h*.5f-cy*zoom;
        RectF entire=new RectF(left,top,left+imgW*zoom,top+imgH*zoom);
        canvas.drawBitmap(photo,null,entire,imagePaint);
        RectF target=new RectF(left+bounds.left*imgW*zoom,top+bounds.top*imgH*zoom,
            left+bounds.right*imgW*zoom,top+bounds.bottom*imgH*zoom);
        float padding=5*density;
        target.inset(-padding,-padding);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(0xff05101a);
        line.setStrokeWidth(7*density);
        canvas.drawRoundRect(target,14*density,14*density,line);
        line.setColor(accent);
        line.setStrokeWidth(3*density);
        canvas.drawRoundRect(target,14*density,14*density,line);
        line.setStyle(Paint.Style.FILL);
        text.setColor(0xe6061526);
        canvas.drawRoundRect(new RectF(12*density,175*density,w-12*density,214*density),
            12*density,12*density,text);
        text.setColor(Color.WHITE);
        text.setTextSize(13*density);
        text.setFakeBoldText(true);
        canvas.drawText("LOCKED + ZOOMED  •  FIND AGAIN FOR LIVE CAMERA",
            20*density,199*density,text);
    }
}
