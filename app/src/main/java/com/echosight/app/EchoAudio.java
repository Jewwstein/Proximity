package com.echosight.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.util.Log;
import java.util.Arrays;
import java.util.Locale;

/**
 * Experimental non-directional phone acoustic echogram.
 * AudioTrack.MODE_STREAM with device-reported minimum buffers, rather than the
 * 10 ms MODE_STATIC track rejected by some Android audio HALs.
 * No range result is a safe navigation/collision detection measurement.
 */
public final class EchoAudio {
    public interface Listener { void onReading(Reading r); }
    public static final class Reading {
        public final boolean calibrated;
        public final float distanceMeters, strength;
        public final float[] bands;
        public final String message;
        public Reading(boolean c,float d,float s,float[] b,String m) {
            calibrated=c;distanceMeters=d;strength=s;bands=b;message=m;
        }
    }
    private static final String TAG="EchoSight-Audio";
    private static final float SPEED_OF_SOUND=343f;
    private final float[] smoothed=new float[12];
    private volatile boolean running=false, audibleFallback=false;
    private Thread captureThread, playbackThread;
    private AudioRecord recorder;
    private AudioTrack player;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor suppression;
    private AutomaticGainControl gain;
    private Listener listener;
    private int rate=48000, outputChannels=1;
    private short[] chirp;
    private double chirpPower=1;
    private int audioChecks=0;

    public synchronized boolean isRunning(){return running;}
    public synchronized boolean isAudibleFallback(){return audibleFallback;}
    public synchronized void setAudibleFallback(boolean on){audibleFallback=on;}
    public synchronized void start(Listener cb){
        if(running || (captureThread!=null && captureThread.isAlive()))return;
        listener=cb;running=true;
        captureThread=new Thread(this::runCapture,"EchoSight-AcousticCapture");
        captureThread.setDaemon(true);
        captureThread.start();
    }
    public synchronized void stop(){
        running=false;
        if(playbackThread!=null)playbackThread.interrupt();
        if(captureThread!=null)captureThread.interrupt();
    }
    private void emit(String message){emit(new Reading(false,0,0,new float[12],message));}
    private void emit(Reading reading){Listener l=listener;if(l!=null)l.onReading(reading);}
    private void newChirp(){
        int length=Math.round(rate*.013f);
        chirp=new short[length];
        double energy=0;
        final double f0=audibleFallback?9100:14500;
        final double f1=audibleFallback?12000:16900;
        for(int i=0;i<length;i++){
            double time=i/(double)rate,duration=length/(double)rate;
            double phase=2*Math.PI*(f0*time+(f1-f0)*time*time/(2*duration));
            double window=.5-.5*Math.cos(2*Math.PI*i/(length-1));
            chirp[i]=(short)Math.round(2100*window*Math.sin(phase));
            energy+=chirp[i]*(double)chirp[i];
        }
        chirpPower=energy;
    }
    private AudioRecord createRecorder(int frequency) throws Exception {
        int minimum=AudioRecord.getMinBufferSize(frequency,
            AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0)throw new Exception("Input "+frequency+" Hz unsupported");
        int buffer=Math.max(minimum*2,32768);
        int[] sources={
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC};
        for(int source:sources){
            AudioRecord r=null;
            try{
                r=new AudioRecord(source,frequency,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,buffer);
                if(r.getState()==AudioRecord.STATE_INITIALIZED){
                    Log.i(TAG,"Microphone source="+source+" sampleRate="+frequency);
                    return r;
                }
            }catch(Throwable error){
                Log.w(TAG,"Input source "+source+" rejected",error);
            }
            if(r!=null)try{r.release();}catch(Throwable ignored){}
        }
        throw new Exception("Mic input "+frequency+" Hz couldn't initialize");
    }
    private AudioTrack createPlayer(int frequency) throws Exception {
        // MODE_STREAM buffers should be at least getMinBufferSize() bytes.
        // A 13-ms MODE_STATIC buffer frequently fails initialization.
        for(int channel:new int[]{AudioFormat.CHANNEL_OUT_MONO,AudioFormat.CHANNEL_OUT_STEREO}){
            int min=AudioTrack.getMinBufferSize(frequency,channel,
                AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)continue;
            int channels=channel==AudioFormat.CHANNEL_OUT_STEREO?2:1;
            int bytes=Math.max(min*2,frequency*channels*2/5);
            int aligned=bytes+(bytes%(2*channels)==0?0:(2*channels-bytes%(2*channels)));
            AudioTrack p=null;
            try{
                p=new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(frequency).setChannelMask(channel).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(aligned).build();
                if(p.getState()==AudioTrack.STATE_INITIALIZED){
                    outputChannels=channels;
                    p.setVolume(.40f);
                    Log.i(TAG,"Audio output initialized: "+frequency+" Hz, channels="+channels+
                        ", buffer="+aligned);
                    return p;
                }
            }catch(Throwable error){
                Log.w(TAG,"Speaker format rejected",error);
            }
            if(p!=null)try{p.release();}catch(Throwable ignored){}
        }
        throw new Exception("Speaker stream format unavailable at "+frequency+" Hz");
    }
    private void configure(){
        try{
            int id=recorder.getAudioSessionId();
            if(AcousticEchoCanceler.isAvailable()){
                aec=AcousticEchoCanceler.create(id);
                if(aec!=null)aec.setEnabled(false);
            }
            if(NoiseSuppressor.isAvailable()){
                suppression=NoiseSuppressor.create(id);
                if(suppression!=null)suppression.setEnabled(false);
            }
            if(AutomaticGainControl.isAvailable()){
                gain=AutomaticGainControl.create(id);
                if(gain!=null)gain.setEnabled(false);
            }
        }catch(Throwable e){Log.w(TAG,"DSP cannot be disabled; measurements may be unreliable",e);}
    }
    private void initialize() throws Exception {
        String last="";
        for(int hz:new int[]{48000,44100}){
            try{
                recorder=createRecorder(hz);
                player=createPlayer(hz);
                rate=hz;
                newChirp();
                configure();
                return;
            }catch(Exception e){
                last=e.getMessage();
                if(recorder!=null){try{recorder.release();}catch(Exception ignored){}recorder=null;}
                if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}
            }
        }
        throw new Exception("Mic/speaker cannot share a supported PCM format: "+last);
    }
    private short[] playbackPacket(){
        int length=(int)(rate*.16f);
        int samples=length*outputChannels;
        short[] packet=new short[samples];
        int offset=(int)(rate*.018f);
        for(int i=0;i<chirp.length && offset+i<length;i++){
            for(int ch=0;ch<outputChannels;ch++)
                packet[(offset+i)*outputChannels+ch]=chirp[i];
        }
        return packet;
    }
    private void playPulses(){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        try{
            short[] burst=playbackPacket();
            player.play();
            while(running){
                int written=0;
                while(running && written<burst.length){
                    int n=player.write(burst,written,burst.length-written,
                        AudioTrack.WRITE_BLOCKING);
                    if(n<=0)throw new IllegalStateException("AudioTrack streaming write="+n);
                    written+=n;
                }
                if(!running)break;
                // Separate chirps so echoes from different bursts do not alias.
                try{Thread.sleep(250);}catch(InterruptedException ignored){break;}
            }
        }catch(Throwable error){
            Log.w(TAG,"Acoustic playback interrupted",error);
            if(running)emit("Speaker playback error: "+error.getClass().getSimpleName()+
                " "+error.getMessage());
            running=false;
        }
    }
    private void runCapture(){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        try{
            initialize();
            recorder.startRecording();
            if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                throw new Exception("Mic failed to start recording");
            emit("Audio ready: "+rate/1000f+" kHz · "+
                (audibleFallback?"audible chirps":"high-frequency chirps")+" · listening…");
            playbackThread=new Thread(this::playPulses,"EchoSight-AcousticOutput");
            playbackThread.setDaemon(true);
            playbackThread.start();
            short[] block=new short[8192];
            short[] overlap=new short[1200];
            int overlapLength=0;
            while(running){
                int n=recorder.read(block,0,block.length,AudioRecord.READ_BLOCKING);
                if(n<=0){
                    if(running)emit("Microphone returned "+n+" samples (recording unavailable)");
                    continue;
                }
                // Carry-over lets reflections survive a read-buffer boundary.
                short[] window=new short[overlapLength+n];
                System.arraycopy(overlap,0,window,0,overlapLength);
                System.arraycopy(block,0,window,overlapLength,n);
                if(++audioChecks%2==0 && window.length>chirp.length+300)
                    emit(analyze(window,window.length));
                overlapLength=Math.min(overlap.length,window.length);
                System.arraycopy(window,window.length-overlapLength,overlap,0,overlapLength);
            }
        }catch(SecurityException e){
            running=false;
            emit("Microphone permission denied by Android");
        }catch(Throwable e){
            Log.e(TAG,"Acoustic initialization failed",e);
            running=false;
            emit("Audio initialization error: "+String.valueOf(e.getMessage()));
        }finally{
            running=false;
            if(playbackThread!=null)playbackThread.interrupt();
            try{if(recorder!=null)recorder.stop();}catch(Throwable ignored){}
            try{if(player!=null)player.pause();}catch(Throwable ignored){}
            try{if(recorder!=null)recorder.release();}catch(Throwable ignored){}
            try{if(player!=null)player.release();}catch(Throwable ignored){}
            try{if(aec!=null)aec.release();}catch(Throwable ignored){}
            try{if(suppression!=null)suppression.release();}catch(Throwable ignored){}
            try{if(gain!=null)gain.release();}catch(Throwable ignored){}
            recorder=null;player=null;aec=null;suppression=null;gain=null;
        }
    }
    private double match(short[] data,int at){
        if(at<0||at+chirp.length>data.length)return 0;
        double dot=0,power=0;
        for(int i=0;i<chirp.length;i++){
            double v=data[at+i];
            dot+=v*chirp[i];
            power+=v*v;
        }
        return power>100000 ? Math.abs(dot)/Math.sqrt(chirpPower*power) : 0;
    }
    private Reading analyze(short[] samples,int n){
        double directScore=0;int direct=-1;
        // A single mic cannot estimate direction. Peak is audio direct coupling,
        // not the location of a visible object.
        int end=n-chirp.length-1100;
        if(end<=0)return new Reading(false,0,0,smoothed.clone(),"Waiting for audio samples");
        for(int i=0;i<end;i+=5){
            double v=match(samples,i);
            if(v>directScore){directScore=v;direct=i;}
        }
        if(direct<0 || directScore<.28){
            Arrays.fill(smoothed,0);
            return new Reading(false,0,0,smoothed.clone(),
                "Speaker and mic active, but no reliable chirp received. Try AUDIBLE mode.");
        }
        float closest=0,highest=0;
        for(int b=0;b<smoothed.length;b++){
            float meters=.40f+b*.28f;
            int delay=(int)Math.round((2*meters/SPEED_OF_SOUND)*rate);
            double correlation=0;
            for(int dx=-16;dx<=16;dx+=4)
                correlation=Math.max(correlation,match(samples,direct+delay+dx));
            float intensity=(float)Math.max(0,Math.min(1,(correlation-.16)/.45));
            smoothed[b]=.62f*smoothed[b]+.38f*intensity;
            if(smoothed[b]>highest){highest=smoothed[b];closest=meters;}
        }
        boolean plausible=highest>=.50f && directScore>=.42;
        if(plausible)
            return new Reading(true,closest,highest,smoothed.clone(),
                String.format(Locale.US,"Possible reflection ~%.1f m (unverified; no direction)",closest));
        return new Reading(true,0,highest,smoothed.clone(),
            "Speaker/mic synchronized · no reliable distance reflection");
    }
}
