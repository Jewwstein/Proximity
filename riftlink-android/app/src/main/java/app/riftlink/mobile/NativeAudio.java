package app.riftlink.mobile;

import android.media.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

// Native stereo system-audio playback. Stream contains signed PCM16 2-channel
// WASAPI loopback packets; bounded buffering drops stale audio rather than lagging.
final class NativeAudio {
    private AudioTrack track;
    private int rate;
    private volatile boolean stopped=false;
    private long lastStatus;
    private final Consumer<String> notify;
    NativeAudio(Consumer<String> n){notify=n;}
    void play(DataInputStream in)throws Exception{
        while(!stopped){
            int n=in.readInt();int hz=in.readInt();
            if(n<0||n>4*192000||hz<8000||hz>192000)throw new IOException("Invalid audio packet");
            byte[] b=new byte[n];in.readFully(b);
            if(stopped)break;
            if(track==null||rate!=hz){
                closeTrack();rate=hz;
                int min=AudioTrack.getMinBufferSize(hz,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
                if(min<=0)throw new IOException("Audio format not supported");
                int capacity=Math.max(min,hz*4/20);
                track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(hz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(capacity).setTransferMode(AudioTrack.MODE_STREAM).build();
                track.play();notify.accept("Audio running "+hz+" Hz");
            }
            // When the network falls behind, discard old audio instead of building delay.
            if(track.getPlayState()==AudioTrack.PLAYSTATE_PLAYING){
                int head=0;
                while(head<n&&!stopped){
                    int wrote=track.write(b,head,n-head,AudioTrack.WRITE_NON_BLOCKING);
                    if(wrote>0)head+=wrote;
                    else {break;} // late chunk discarded
                }
            }
        }
    }
    void closeTrack(){
        if(track!=null){try{track.pause();track.flush();track.release();}catch(Exception ignored){}track=null;}
    }
    synchronized void close(){stopped=true;closeTrack();}
}
