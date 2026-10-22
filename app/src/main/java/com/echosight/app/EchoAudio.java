package com.echosight.app;

import android.Manifest;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.util.Log;

import java.util.Arrays;

/**
 * Experimental single-channel acoustic echogram.
 *
 * This is NOT a directional sonar or collision-avoidance sensor. Correlations
 * can be distorted by built-in DSP, microphone/speaker routing, and echoes.
 * Distances are only tentative round-trip reflection bands, not a 3-D map.
 */
public final class EchoAudio {
    public interface Listener {
        void onReading(Reading reading);
    }
    public static final class Reading {
        public final boolean calibrated;
        public final float distanceMeters;
        public final float strength;
        public final float[] bands;
        public final String message;
        Reading(boolean calibrated,float distance,float strength,float[] bands,String message){
            this.calibrated=calibrated;
            distanceMeters=distance;this.strength=strength;this.bands=bands;this.message=message;
        }
    }
    private static final String TAG="EchoSight-Sonar";
    private static final int RATE=48000;
    private static final int CHIRP_SIZE=480;
    private static final int READ_SIZE=8192;
    private final short[] chirp=new short[CHIRP_SIZE];
    private final double refPower;
    private volatile boolean running=false;
    private Thread thread;
    private AudioRecord microphone;
    private AudioTrack speaker;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor suppressor;
    private AutomaticGainControl agc;
    private Listener callback;
    private final float[] smooth=new float[12];
    private int pingCount=0;

    public EchoAudio() {
        double energy=0;
        final double fStart=15400, fEnd=17700;
        for(int i=0;i<chirp.length;i++){
            double time=i/(double)RATE;
            double duration=chirp.length/(double)RATE;
            double phase=2*Math.PI*(fStart*time + (fEnd-fStart)*time*time/(2*duration));
            double envelope=.5-.5*Math.cos(2*Math.PI*i/(chirp.length-1));
            // Deliberately low output level; user must opt into audible/near-ultrasonic chirps.
            double value=Math.sin(phase)*envelope;
            chirp[i]=(short)Math.round(value*2400);
            energy+=chirp[i]*(double)chirp[i];
        }
        refPower=energy;
    }
    public synchronized boolean isRunning(){return running;}
    public synchronized void start(Listener listener) {
        if(running)return;
        callback=listener;
        running=true;
        thread=new Thread(this::loop,"EchoSight-48k-sonar");
        thread.setDaemon(true);
        thread.start();
    }
    public synchronized void stop(){
        running=false;
        if(thread!=null)thread.interrupt();
    }
    private void emit(Reading r){
        Listener l=callback;if(l!=null)l.onReading(r);
    }
    private static void disableDsp(AudioRecord record, EchoAudio self) {
        try {
            int id=record.getAudioSessionId();
            if(AcousticEchoCanceler.isAvailable()){
                self.aec=AcousticEchoCanceler.create(id);
                if(self.aec!=null)self.aec.setEnabled(false);
            }
            if(NoiseSuppressor.isAvailable()){
                self.suppressor=NoiseSuppressor.create(id);
                if(self.suppressor!=null)self.suppressor.setEnabled(false);
            }
            if(AutomaticGainControl.isAvailable()){
                self.agc=AutomaticGainControl.create(id);
                if(self.agc!=null)self.agc.setEnabled(false);
            }
        }catch(Throwable e){Log.w(TAG,"DSP configuration unsupported",e);}
    }
    private void loop(){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        try {
            int recordingBytes=Math.max(AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT),READ_SIZE*4);
            try {
                microphone=new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,RATE,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,recordingBytes);
                if(microphone.getState()!=AudioRecord.STATE_INITIALIZED)throw new Exception("UNPROCESSED capture unavailable");
            }catch(Throwable rawNotSupported){
                if(microphone!=null){microphone.release();microphone=null;}
                microphone=new AudioRecord(MediaRecorder.AudioSource.MIC,RATE,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,recordingBytes);
            }
            if(microphone.getState()!=AudioRecord.STATE_INITIALIZED)
                throw new Exception("Microphone does not support 48 kHz mono");
            disableDsp(microphone,this);
            speaker=new AudioTrack.Builder().setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(CHIRP_SIZE*2)
                .build();
            if(speaker.getState()!=AudioTrack.STATE_INITIALIZED)throw new Exception("Speaker unavailable");
            speaker.setVolume(.45f);
            if(speaker.write(chirp,0,chirp.length)<chirp.length)throw new Exception("Could not prepare chirp");
            microphone.startRecording();
            if(microphone.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                throw new Exception("Microphone could not start");
            emit(new Reading(false,0,0,new float[12],"Listening for reflected chirps…"));
            short[] samples=new short[READ_SIZE];
            short[] clear=new short[512];
            while(running){
                microphone.read(clear,0,clear.length,AudioRecord.READ_BLOCKING);
                try{
                    speaker.stop();
                    speaker.reloadStaticData();
                    speaker.setPlaybackHeadPosition(0);
                    speaker.play();
                }catch(IllegalStateException e){throw new Exception("Speaker pulse restart failed",e);}
                int amount=0;
                while(running && amount<samples.length){
                    int n=microphone.read(samples,amount,samples.length-amount,AudioRecord.READ_BLOCKING);
                    if(n<=0)break;
                    amount+=n;
                }
                if(amount>=7000)emit(analyze(samples,amount));
                if(++pingCount%5==0)Log.d(TAG,"Sonar pings="+pingCount);
                if(!running)break;
                try{Thread.sleep(300);}catch(InterruptedException ignored){break;}
            }
        }catch(SecurityException e) {
            emit(new Reading(false,0,0,new float[12],"Microphone permission required"));
        }catch(Throwable e){
            Log.e(TAG,"Acoustic test unavailable",e);
            emit(new Reading(false,0,0,new float[12],
                "Sound ranging unavailable: "+String.valueOf(e.getMessage())));
        }finally{
            try{if(microphone!=null)microphone.stop();}catch(Exception ignored){}
            try{if(speaker!=null)speaker.stop();}catch(Exception ignored){}
            try{if(microphone!=null)microphone.release();}catch(Exception ignored){}
            try{if(speaker!=null)speaker.release();}catch(Exception ignored){}
            try{if(aec!=null)aec.release();}catch(Exception ignored){}
            try{if(suppressor!=null)suppressor.release();}catch(Exception ignored){}
            try{if(agc!=null)agc.release();}catch(Exception ignored){}
            microphone=null;speaker=null;aec=null;suppressor=null;agc=null;
            running=false;
        }
    }
    private double match(short[] data,int at){
        if(at<0||at+CHIRP_SIZE>data.length)return 0;
        double dot=0,energy=0;
        for(int i=0;i<CHIRP_SIZE;i++){
            double sample=data[at+i],reference=chirp[i];
            dot+=sample*reference;energy+=sample*sample;
        }
        if(energy<100000)return 0;
        return Math.abs(dot)/Math.sqrt(refPower*energy);
    }
    private Reading analyze(short[] samples,int length){
        int direct=0;double directScore=0;
        final int maxDirect=Math.min(length-CHIRP_SIZE-1300,4100);
        for(int i=0;i<maxDirect;i+=4){
            double score=match(samples,i);
            if(score>directScore){directScore=score;direct=i;}
        }
        int refine=Math.max(0,direct-6);
        for(int i=refine;i<Math.min(maxDirect,direct+7);i++){
            double score=match(samples,i);
            if(score>directScore){directScore=score;direct=i;}
        }
        if(directScore<.35) {
            Arrays.fill(smooth,0);
            return new Reading(false,0,0,smooth.clone(),
                "No reliable direct chirp. Phone speaker/mic may filter high frequencies.");
        }
        // No meaningful azimuth measurement is possible with a single microphone.
        // Distances are relative-delay candidates measured after direct coupling.
        float bestMeters=0,bestConfidence=0;
        for(int bin=0;bin<smooth.length;bin++){
            float meters=.35f+bin*.27f;
            int relative=(int)Math.round(meters*2*RATE/343.);
            int location=direct+relative;
            double top=0;
            for(int delta=-20;delta<=20;delta+=5){
                int at=location+delta;
                if(at>=0&&at+CHIRP_SIZE<length)
                    top=Math.max(top,match(samples,at));
            }
            // Direct coupling multipath/sidelobes are significant on phones.
            float confidence=(float)Math.max(0,(top-.14)/.58);
            smooth[bin]=.64f*smooth[bin]+.36f*confidence;
            if(smooth[bin]>bestConfidence){
                bestConfidence=smooth[bin];
                bestMeters=meters;
            }
        }
        if(bestConfidence>=.47f && directScore>=.43) {
            return new Reading(true,bestMeters,bestConfidence,smooth.clone(),
                String.format(java.util.Locale.US,"Possible echo band ~%.1f m (no direction)",bestMeters));
        }
        return new Reading(true,0,bestConfidence,smooth.clone(),
            "Direct chirp heard · no trustworthy reflection measured");
    }
}
