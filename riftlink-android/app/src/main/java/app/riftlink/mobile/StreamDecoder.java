package app.riftlink.mobile;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;

final class StreamDecoder {
    private final Surface surface;
    private int width,height;
    private final Consumer<String> status;
    private MediaCodec decoder;
    private long frames=0,lastReport=0,lastTimestamp=-1;
    private volatile boolean closed=false;
    StreamDecoder(Surface s,int w,int h,Consumer<String> cb){surface=s;width=w;height=h;status=cb;}
    static final class NAL {int type;byte[] bytes;NAL(int t,byte[] d){type=t;bytes=d;}}
    static List<NAL> nals(byte[] data){
        ArrayList<NAL> out=new ArrayList<>();
        int i=0;
        while(i<data.length-3){
            int n=0;
            if(data[i]==0&&data[i+1]==0){
                if(data[i+2]==1)n=3;
                else if(i+3<data.length&&data[i+2]==0&&data[i+3]==1)n=4;
            }
            if(n==0){i++;continue;}
            int start=i,head=i+n,j=head+1;
            while(j<data.length-3 && !(data[j]==0&&data[j+1]==0&&(data[j+2]==1||(data[j+2]==0&&data[j+3]==1))))j++;
            if(j>=data.length-3)j=data.length;
            if(head<j)out.add(new NAL(data[head]&31,Arrays.copyOfRange(data,start,j)));
            i=j;
        }
        return out;
    }
    synchronized void frame(byte[] packet,boolean key,long micros,int frameWidth,int frameHeight) {
        if(closed)return;
        try{
            if(frameWidth!=width || frameHeight!=height){
                reset();
                width=frameWidth;height=frameHeight;
                status.accept("Adaptive video "+width+"×"+height);
            }
            if(decoder==null){
                if(!key)return;
                byte[] sps=null,pps=null;
                for(NAL n:nals(packet)){if(n.type==7)sps=n.bytes;else if(n.type==8)pps=n.bytes;}
                if(sps==null||pps==null)return;
                MediaFormat fmt=MediaFormat.createVideoFormat("video/avc",width,height);
                fmt.setByteBuffer("csd-0",ByteBuffer.wrap(sps));
                fmt.setByteBuffer("csd-1",ByteBuffer.wrap(pps));
                if(Build.VERSION.SDK_INT>=30)fmt.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
                decoder=MediaCodec.createDecoderByType("video/avc");
                decoder.configure(fmt,surface,null,0);
                decoder.start();
                lastReport=SystemClock.elapsedRealtime();lastTimestamp=-1;frames=0;
                status.accept("H.264 decoder running • "+width+"×"+height);
            }
            // Very short dequeue avoids blocking the network reader behind a slow
            // hardware decoder. Resync from a keyframe after a jam.
            int idx=decoder.dequeueInputBuffer(1000);
            if(idx>=0){
                ByteBuffer b=decoder.getInputBuffer(idx);
                if(b!=null&&b.capacity()>=packet.length){
                    b.clear();b.put(packet);
                    long pts=Math.max(lastTimestamp+1,micros);
                    lastTimestamp=pts;
                    decoder.queueInputBuffer(idx,0,packet.length,pts,0);
                }else{
                    decoder.queueInputBuffer(idx,0,0,Math.max(0,micros),0);
                    if(key)status.accept("Decoder buffer too small; awaiting next IDR");
                }
            }
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            // Drain all available frames; the latest surface output is what matters for responsiveness.
            for(int k=0;k<8;k++){
                int out=decoder.dequeueOutputBuffer(info,0);
                if(out>=0){decoder.releaseOutputBuffer(out,info.size>0);frames++;}
                else if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)continue;
                else break;
            }
            long now=SystemClock.elapsedRealtime();
            if(now-lastReport>=1800){
                double fps=frames*1000.0/Math.max(1,now-lastReport);
                status.accept(String.format(Locale.US,"RiftLink native • %.0f decoded fps • %d×%d",fps,width,height));
                lastReport=now;frames=0;
            }
        }catch(Exception e){
            status.accept("Decoder restarting: "+e.getMessage());reset();
        }
    }
    synchronized void reset(){
        if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}try{decoder.release();}catch(Exception ignored){}decoder=null;}
    }
    synchronized void close(){closed=true;reset();surface.release();}
}
