package com.echosight.app;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Enhances actual camera photons. Does NOT hallucinate edges in the dark.
 * Spatial lines always originate from camera pixels, NEVER from mono sonar.
 */
public final class VisualPipeline {
    public static final int FUSION=0, EDGES=1, ENHANCED=2;
    public static final class Frame {
        public final Bitmap bitmap;
        public final boolean insufficientLight;
        public final int peakLuminance;
        public final int edgePixels;
        Frame(Bitmap bmp,boolean dark,int peak,int edges){
            bitmap=bmp;insufficientLight=dark;peakLuminance=peak;edgePixels=edges;
        }
    }
    private VisualPipeline(){}

    public static Frame render(Bitmap input,int mode,float gainControl) {
        int originalWidth=input.getWidth(),originalHeight=input.getHeight();
        int w=Math.min(320,originalWidth);
        int h=Math.max(2,Math.round(originalHeight*(w/(float)originalWidth)));
        Bitmap resized=Bitmap.createScaledBitmap(input,w,h,true);
        int total=w*h;
        int[] rgb=new int[total];
        resized.getPixels(rgb,0,w,0,0,w,h);
        if(resized!=input)resized.recycle();
        int[] levels=new int[256];
        byte[] originalGray=new byte[total];
        for(int i=0;i<total;i++){
            int c=rgb[i];
            int value=((c>>16&255)*77+(c>>8&255)*150+(c&255)*29)>>8;
            originalGray[i]=(byte)value;
            levels[value]++;
        }
        int acc=0,p90=0;
        for(int i=0;i<256;i++){
            acc+=levels[i];
            if(acc>=total*.90f){p90=i;break;}
        }
        boolean pitchDark=p90<6; // no camera evidence to enhance
        float boost=Math.min(9f,Math.max(1f,185f/Math.max(10f,p90)));
        boost*=gainControl;
        boost=Math.max(1f,Math.min(12f,boost));
        int[] adjusted=new int[total];
        byte[] blurred=new byte[total];
        for(int i=0;i<total;i++){
            int c=rgb[i];
            float red=(c>>16)&255, green=(c>>8)&255, blue=c&255;
            // gamma/contrast mapping + a blue-white channel bias for outlines
            int rr=Math.min(255,Math.round((float)(255*Math.pow(Math.min(1,red*boost/255),.78))));
            int gg=Math.min(255,Math.round((float)(255*Math.pow(Math.min(1,green*boost/255),.78))));
            int bb=Math.min(255,Math.round((float)(255*Math.pow(Math.min(1,blue*boost/255),.78))));
            adjusted[i]=Color.rgb(rr,gg,bb);
        }
        // Fast local smoothing prevents low-light sensor noise becoming sharp
        // made-up contours. Actual camera edge evidence is mandatory.
        for(int y=1;y<h-1;y++){
            int offset=y*w;
            for(int x=1;x<w-1;x++){
                int id=offset+x;
                int sum=0;
                for(int dy=-1;dy<=1;dy++)
                    for(int dx=-1;dx<=1;dx++)
                        sum+=originalGray[id+dy*w+dx]&255;
                blurred[id]=(byte)(sum/9);
            }
        }
        int[] display=new int[total];int edges=0;
        int threshold=Math.max(17,Math.round((p90<28?2.3f:1.0f)*21));
        for(int y=0;y<h;y++){
            int offset=y*w;
            for(int x=0;x<w;x++){
                int i=offset+x;int color=adjusted[i];
                if(pitchDark){
                    // Camera black means "unknown", not wall/floor contours.
                    display[i]=Color.rgb(0,7,14);
                    continue;
                }
                int edgeStrength=0;
                if(x>=2 && y>=2 && x<w-2 && y<h-2){
                    int left=blurred[i-1]&255,right=blurred[i+1]&255;
                    int above=blurred[i-w]&255,below=blurred[i+w]&255;
                    edgeStrength=Math.abs(right-left)+Math.abs(below-above);
                }
                boolean line=edgeStrength>=threshold;
                if(line)edges++;
                if(mode==EDGES) {
                    display[i]=line?Color.rgb(83,232,248):Color.rgb(1,12,22);
                } else if(mode==ENHANCED) {
                    display[i]=color;
                } else {
                    int r=color>>16&255,g=color>>8&255,b=color&255;
                    r=(int)(r*.43f);g=(int)(g*.51f);b=(int)(b*.72f);
                    if(line){r=Math.min(255,r+54);g=Math.min(255,g+115);b=Math.min(255,b+120);}
                    display[i]=Color.rgb(r,g,b);
                }
            }
        }
        Bitmap output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        output.setPixels(display,0,w,0,0,w,h);
        return new Frame(output,pitchDark,p90,edges);
    }
}
