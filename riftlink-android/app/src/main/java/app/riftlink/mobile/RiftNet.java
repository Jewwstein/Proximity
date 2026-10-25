package app.riftlink.mobile;

import android.app.*;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.os.Environment;
import android.content.*;
import android.os.*;
import org.json.*;
import javax.net.ssl.*;
import java.net.*;
import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class RiftNet {
    final MainActivity activity;
    final String host;
    final ExecutorService work=Executors.newCachedThreadPool();
    final ExecutorService inputWork=Executors.newSingleThreadExecutor();
    final java.util.concurrent.atomic.AtomicReference<byte[]> newestMove=new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicBoolean moveQueued=new java.util.concurrent.atomic.AtomicBoolean(false);
    volatile boolean running=true, streaming=false;
    final java.util.concurrent.atomic.AtomicBoolean authErrorShown=new java.util.concurrent.atomic.AtomicBoolean(false);
    volatile String token="", fingerprint="";
    volatile SSLSocket inputSocket;
    volatile OutputStream inputOut;
    volatile HttpsURLConnection streamConnection, audioConnection;
    volatile boolean audioStreaming=false;
    volatile NativeAudio audioPlayer;
    volatile boolean inputConnected=false;
    long lastMove=0;
    interface Discovery{void error(String e);void fingerprint(String fp,boolean known);}
    interface AuthListener{void complete(String token,int width,int height,boolean gamepad);void error(String e);}
    RiftNet(MainActivity a,String h){activity=a;host=h;}
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02X",b&255));return s.toString();}
    static String sha256(java.security.cert.Certificate c) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
    }
    X509TrustManager manager(boolean probe) {
        return new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] chain,String authType) throws CertificateException{
                throw new CertificateException("RiftLink does not accept client certificates");
            }
            public void checkServerTrusted(X509Certificate[] chain,String authType) throws CertificateException{
                if(chain==null||chain.length==0)throw new CertificateException("Missing PC certificate");
                if(probe)return; // Certificate discovery only; no HTTP data sent until user confirms fingerprint
                try {
                    String fp=sha256(chain[0]);
                    if(!fp.equals(fingerprint))throw new CertificateException("PC identity changed; reconnect and confirm it");
                }catch(Exception ex){throw new CertificateException(ex);}
            }
        };
    }
    SSLContext context(boolean probe)throws Exception {
        SSLContext ctx=SSLContext.getInstance("TLS");
        ctx.init(null,new TrustManager[]{manager(probe)},new SecureRandom());
        return ctx;
    }
    void discoverFingerprint(Discovery cb) {
        work.execute(()->{
            try {
                SSLContext ctx=context(true);
                try(SSLSocket sock=(SSLSocket)ctx.getSocketFactory().createSocket()){
                    sock.connect(new InetSocketAddress(host,38775),5000);
                    sock.setSoTimeout(5000);sock.startHandshake();
                    String fp=sha256(sock.getSession().getPeerCertificates()[0]);
                    fingerprint=fp;
                    android.content.SharedPreferences prefs=activity.getPreferences(0);
                    String old=prefs.getString("trusted-"+host,"");
                    // Same pinned Windows certificate may be reached by Wi-Fi IP or VPN IP.
                    boolean samePairedComputer=!prefs.getString("token-cert-"+fp,"").isEmpty();
                    cb.fingerprint(fp,fp.equals(old)||samePairedComputer);
                }
            }catch(Exception ex){cb.error(ex.toString());}
        });
    }
    void pin(String fp){fingerprint=fp;activity.getPreferences(0).edit().putString("trusted-"+host,fp).apply();}
    HttpsURLConnection open(String path,String method) throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)new URL("https://"+host+":38775"+path).openConnection();
        c.setSSLSocketFactory(context(false).getSocketFactory());
        c.setHostnameVerifier((hostname,session)->hostname.equalsIgnoreCase(host)); // Explicit SHA-256 host pin verified by trust manager
        c.setConnectTimeout(6000);c.setReadTimeout(12000);c.setRequestMethod(method);
        c.setRequestProperty("Cache-Control","no-store");
        if(!token.isEmpty())c.setRequestProperty("X-RiftLink-Token",token);
        return c;
    }
    static String body(InputStream s)throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[2048];int n;
        while((n=s.read(buf))!=-1){b.write(buf,0,n);if(b.size()>16384)break;}
        return b.toString("UTF-8");
    }
    void authenticate(String pin,String saved,AuthListener listener){
        work.execute(()->{
            try{
                token=saved==null?"":saved;
                JSONObject info=null;
                if(!token.isEmpty()){
                    HttpsURLConnection check=null;
                    try {
                        check=open("/api/info","GET");
                        int code=check.getResponseCode();
                        if(code==200) info=new JSONObject(body(check.getInputStream()));
                        if(code==401){
                            token="";
                            activity.getPreferences(0).edit().remove("token-"+host)
                                .remove("token-cert-"+fingerprint).apply();
                        }else if(code!=200){
                            throw new IOException("PC responded HTTP "+code);
                        }
                    } catch(IOException transientError){
                        // Never throw away a previously saved token for a transient network error.
                        throw transientError;
                    } finally {if(check!=null)check.disconnect();}
                }
                if(info==null){
                    if(!pin.matches("[0-9]{6}")){
                        throw new Exception("Pairing expired. Enter the PC PIN once; future restarts reconnect automatically.");
                    }
                    HttpsURLConnection connection=open("/api/pair","POST");
                    try {
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type","application/json");
                        try(OutputStream out=connection.getOutputStream()){
                            out.write(new JSONObject().put("pin",pin).toString().getBytes("UTF-8"));
                        }
                        int result=connection.getResponseCode();
                        if(result!=200) throw new IOException("Pairing rejected (HTTP "+result+")");
                        token=new JSONObject(body(connection.getInputStream())).getString("token");
                    }finally {connection.disconnect();}
                    HttpsURLConnection check=open("/api/info","GET");
                    try {
                        if(check.getResponseCode()!=200)throw new IOException("PC rejected new pairing");
                        info=new JSONObject(body(check.getInputStream()));
                    } finally {check.disconnect();}
                }
                int w=info.optInt("width"),h=info.optInt("height");
                // GPU can be initializing immediately after the Windows GUI launches it.
                for(int retry=0;(w<=0||h<=0)&&retry<12;retry++){
                    Thread.sleep(350);
                    HttpsURLConnection check=open("/api/info","GET");
                    try{
                        if(check.getResponseCode()==200) info=new JSONObject(body(check.getInputStream()));
                    }finally{check.disconnect();}
                    w=info.optInt("width");h=info.optInt("height");
                }
                if(w<=0||h<=0) throw new IOException("PC video engine is still starting; tap Connect again.");
                authErrorShown.set(false);
                listener.complete(token,w,h,info.optBoolean("xinput",false));
            }catch(Exception ex){listener.error(ex.getMessage()==null?ex.toString():ex.getMessage());}
        });
    }
    void onUnauthorized(){
        if(!authErrorShown.compareAndSet(false,true))return;
        streaming=false;
        audioStreaming=false;
        activity.getPreferences(0).edit().remove("token-"+host)
            .remove("token-cert-"+fingerprint).apply();
        activity.runOnUiThread(()->{
            if(activity.net==this){
                activity.showExpiredPairing();
            }
        });
    }
    boolean connectSocket(){
        if(!running)return false;
        if(inputConnected&&inputOut!=null)return true;
        try{
            SSLSocket s=(SSLSocket)context(false).getSocketFactory().createSocket();
            s.connect(new InetSocketAddress(host,38775),5000);
            s.setTcpNoDelay(true);s.setSoTimeout(6000);s.startHandshake();
            byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);
            String key=Base64.getEncoder().encodeToString(nonce);
            String req="GET /api/ws-input?token="+token+" HTTP/1.1\r\nHost: "+host+":38775\r\n"+
                     "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+
                     "\r\nSec-WebSocket-Version: 13\r\n\r\n";
            OutputStream out=s.getOutputStream();out.write(req.getBytes("UTF-8"));out.flush();
            // Read headers directly; no buffered-reader read-ahead into WebSocket frames.
            ByteArrayOutputStream head=new ByteArrayOutputStream();
            int count=0,byteValue;
            while(count<8192&&(byteValue=s.getInputStream().read())>=0){
                head.write(byteValue);count++;
                byte[] bytes=head.toByteArray();int n=bytes.length;
                if(n>=4&&bytes[n-4]==13&&bytes[n-3]==10&&bytes[n-2]==13&&bytes[n-1]==10)break;
            }
            String headers=head.toString("UTF-8");
            if(!headers.startsWith("HTTP/1.1 101"))throw new IOException("Windows controls rejected: "+headers.split("\r\n")[0]);
            s.setSoTimeout(0);inputSocket=s;inputOut=out;inputConnected=true;
            activity.runOnUiThread(()->activity.status.setText("RiftLink connected • trackpad ready"));
            return true;
        }catch(Exception ex){
            inputConnected=false;
            activity.runOnUiThread(()->activity.status.setText("Controls reconnect: "+ex.getMessage()));
            return false;
        }
    }
    void connectInputs(){inputWork.execute(this::connectSocket);}
    void scroll(int wheel){
        if(!running||wheel==0)return;
        try{
            JSONObject payload=new JSONObject();
            payload.put("a","scroll");payload.put("d",Math.max(-2400,Math.min(2400,wheel)));
            byte[] data=payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            inputWork.execute(()->sendInputPayload(data));
        }catch(Exception ignored){}
    }
    void input(String action,String key,float x,float y){
        if(!running)return;
        JSONObject obj=new JSONObject();
        try{
            obj.put("a",action);
            obj.put("x",Math.max(0,Math.min(1,x)));
            obj.put("y",Math.max(0,Math.min(1,y)));
            if(key!=null&&!key.isEmpty())obj.put("t",key);
        }catch(Exception ignored){}
        byte[] payload=obj.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(action.equals("move")){
            long now=SystemClock.elapsedRealtime();
            if(now-lastMove<9)return; lastMove=now;
            // Never enqueue hundreds of obsolete trackpad coordinates behind clicks.
            // Send the latest pointer position once; buttons stay FIFO.
            newestMove.set(payload);
            scheduleMove();
        }else {
            inputWork.execute(()->sendInputPayload(payload));
        }
    }
    void scheduleMove(){
        if(moveQueued.compareAndSet(false,true)){
            inputWork.execute(()->{
                try{
                    byte[] data=newestMove.getAndSet(null);
                    if(data!=null)sendInputPayload(data);
                }finally{
                    moveQueued.set(false);
                    if(newestMove.get()!=null&&running)scheduleMove();
                }
            });
        }
    }
    void sendInputPayload(byte[] payload){
        if(!running)return;
        if(!inputConnected||inputOut==null){if(!connectSocket())return;}
        try{
            byte[] mask=new byte[4];
            new SecureRandom().nextBytes(mask);
            ByteArrayOutputStream frame=new ByteArrayOutputStream(payload.length+16);
            frame.write(0x81);
            if(payload.length<126)frame.write(payload.length|0x80);
            else{frame.write(126|0x80);frame.write((payload.length>>8)&255);frame.write(payload.length&255);}
            frame.write(mask);
            for(int i=0;i<payload.length;i++)frame.write(payload[i]^mask[i%4]);
            inputOut.write(frame.toByteArray());
            inputOut.flush();
        }catch(Exception e){
            inputConnected=false;
            try{if(inputSocket!=null)inputSocket.close();}catch(Exception ignored){}
        }
    }
    volatile long lastVideoPacketAtMs=0;
    volatile long lastVideoConnectedAtMs=0;
    final java.util.concurrent.atomic.AtomicInteger streamEpoch=new java.util.concurrent.atomic.AtomicInteger();
    // An epoch prevents an older reconnect loop from stealing the new stream.
    void startStream(StreamDecoder decoder,Consumer<String> status,Consumer<int[]> resolutionChange){
        final int generation=streamEpoch.incrementAndGet();
        lastVideoPacketAtMs=0;
        lastVideoConnectedAtMs=SystemClock.elapsedRealtime();
        streaming=true;
        work.execute(()->{
            while(running&&streaming&&streamEpoch.get()==generation){
                HttpsURLConnection connection=null;
                try{
                    connection=open("/api/stream?v=2&token="+token,"GET");
                    // Allow brief GPU encoder pauses and Wi-Fi bursts without dropping video.
                    // Input remains on its own independent WebSocket.
                    connection.setReadTimeout(12000);streamConnection=connection;
                    int streamCode=connection.getResponseCode();
                    if(streamCode==401){onUnauthorized();return;}
                    if(streamCode!=200)throw new IOException("Video HTTP "+streamCode);
                    lastVideoConnectedAtMs=SystemClock.elapsedRealtime();
                    long firstPTS=-1,firstArrival=-1,lastFeedbackTime=0,lastIDRRequest=0;
                    int slowFrames=0;
                    long backlogSince=0;
                    boolean needKeyframe=true;
                    int lastWidth=0,lastHeight=0;
                    try(DataInputStream in=new DataInputStream(new BufferedInputStream(connection.getInputStream(),128*1024))){
                        while(running&&streaming&&streamEpoch.get()==generation){
                            int len=in.readInt();
                            boolean key=in.readUnsignedByte()==1;
                            long micros=in.readLong();
                            int frameWidth=in.readUnsignedShort(),frameHeight=in.readUnsignedShort();
                            if(len<=0||len>12*1024*1024||frameWidth<64||frameHeight<64)
                                throw new IOException("Invalid video frame header");
                            byte[] payload=new byte[len];in.readFully(payload);
                            long arrival=SystemClock.elapsedRealtime();
                            if(streamEpoch.get()!=generation || !streaming)break;
                            lastVideoPacketAtMs=arrival;
                            if(lastWidth!=frameWidth||lastHeight!=frameHeight){
                                lastWidth=frameWidth;lastHeight=frameHeight;
                                resolutionChange.accept(new int[]{frameWidth,frameHeight});
                            }
                            if(firstPTS<0 || micros<firstPTS){
                                firstPTS=micros;firstArrival=arrival;
                            }
                            // Estimate growing end-to-end stream backlog from frame age,
                            // excluding the initial one-time network startup delay.
                            long lag=Math.max(0,(arrival-firstArrival)-(micros-firstPTS)/1000);
                            if(arrival-lastFeedbackTime>800){
                                input("feedback","",Math.min(1,lag/1000.0f),0);
                                lastFeedbackTime=arrival;
                            }
                            // A stale TCP buffer makes a beautiful but unplayably late
                            // desktop. Rejoin the live edge; the new stream requests an IDR.
                            // Let short Wi-Fi spikes recover without tearing down H264.
                            // Only a sustained backlog causes a new live-edge connection.
                            if(lag>1600 && arrival-firstArrival>2500){
                                slowFrames++;
                                if(backlogSince==0)backlogSince=arrival;
                            }else{
                                slowFrames=0;backlogSince=0;
                            }
                            // Rejoin only after a sustained multi-second backlog, not
                            // a momentary burst of delayed H.264 packets.
                            if(slowFrames>90 && backlogSince>0 && arrival-backlogSince>2500){
                                throw new IOException("sustained video backlog "+lag+" ms; resyncing live edge");
                            }
                            if(needKeyframe && !key){
                                // Resolution changes and stream restarts need a fresh IDR.
                                // Request it even when no decoder frame has been accepted.
                                if(arrival-lastIDRRequest>750){
                                    input("keyframe","",0,0);
                                    lastIDRRequest=arrival;
                                }
                                continue;
                            }
                            boolean accepted=decoder.frame(payload,key,micros,frameWidth,frameHeight);
                            needKeyframe=!accepted;
                            if(!accepted && arrival-lastIDRRequest>450){
                                input("keyframe","",0,0);
                                lastIDRRequest=arrival;
                            }
                        }
                    }
                }catch(Exception ex){
                    if(running&&streaming&&streamEpoch.get()==generation){
                        String reason=ex instanceof java.net.SocketTimeoutException?
                            "Video stalled; reconnecting decoder (controls remain live)":
                            "Resyncing video: "+ex.getMessage();
                        status.accept(reason);
                        decoder.reset();
                        try{Thread.sleep(450);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
                    }
                }finally{
                    if(connection!=null)connection.disconnect();
                    if(streamConnection==connection)streamConnection=null;
                }
            }
        });
    }
    void startAudio(Consumer<String> notify){
        stopAudio();
        audioStreaming=true;
        work.execute(()->{
            while(running&&audioStreaming){
                try{
                    HttpsURLConnection c=open("/api/audio?token="+token,"GET");
                    audioConnection=c;c.setReadTimeout(0);
                    int audioCode=c.getResponseCode();
                    if(audioCode==401){onUnauthorized();return;}
                    if(audioCode!=200)throw new IOException("Audio HTTP "+audioCode);
                    NativeAudio player=new NativeAudio(notify);audioPlayer=player;
                    try(DataInputStream in=new DataInputStream(new BufferedInputStream(c.getInputStream(),65536))){
                        player.play(in);
                    }finally{player.close();c.disconnect();}
                }catch(Exception ex){
                    if(running&&audioStreaming)try{Thread.sleep(700);}catch(Exception ignored){}
                }
            }
        });
    }
    void stopAudio(){
        audioStreaming=false;
        if(audioPlayer!=null)audioPlayer.close();
        if(audioConnection!=null)audioConnection.disconnect();
    }

    volatile boolean autoFileSyncEnabled=true;
    void startFileAutoSync(Consumer<String> notify) {
        work.execute(()->{
            java.util.HashSet<String> seen=new java.util.HashSet<>();
            boolean primed=false;
            while(running&&streaming&&autoFileSyncEnabled){
                try{
                    HttpsURLConnection c=open("/api/files/list","GET");
                    c.setReadTimeout(10000);
                    if(c.getResponseCode()!=200)throw new IOException("File auto-sync "+c.getResponseCode());
                    JSONArray names=new JSONArray(body(c.getInputStream()));c.disconnect();
                    for(int i=0;i<names.length();i++){
                        String name=names.getString(i);
                        if(primed&&!seen.contains(name))downloadFile(name,notify);
                        seen.add(name);
                    }
                    primed=true;
                }catch(Exception ignored){}
                try{Thread.sleep(4000);}catch(InterruptedException ignored){break;}
            }
        });
    }
    interface FileListListener { void complete(String[] files,String error); }
    void uploadFile(Uri uri, Consumer<String> feedback){
        work.execute(()->{
            try{
                String filename="phone-file";
                try(Cursor cursor=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
                    if(cursor!=null&&cursor.moveToFirst())filename=cursor.getString(0);
                }
                if(filename==null||filename.isEmpty())filename="phone-file";
                HttpsURLConnection c=open("/api/files/upload?name="+URLEncoder.encode(filename,"UTF-8"),"POST");
                c.setDoOutput(true);c.setChunkedStreamingMode(65536);
                c.setRequestProperty("Content-Type","application/octet-stream");
                c.setReadTimeout(90000);
                long copied=0;
                try(InputStream in=activity.getContentResolver().openInputStream(uri);
                    OutputStream out=c.getOutputStream()){
                    if(in==null)throw new IOException("Selected file not accessible");
                    byte[] buf=new byte[64*1024];int n;
                    while((n=in.read(buf))>=0){
                        copied+=n;
                        if(copied>512L*1024*1024)throw new IOException("Maximum transfer size is 512 MB");
                        out.write(buf,0,n);
                    }
                }
                if(c.getResponseCode()!=200)throw new IOException("Upload rejected, HTTP "+c.getResponseCode());
                c.disconnect();
                feedback.accept("Sent "+filename+" to PC desktop / RiftLink Exchange");
            }catch(Exception ex){feedback.accept("File upload failed: "+ex.getMessage());}
        });
    }
    void listFiles(FileListListener callback){
        work.execute(()->{
            try{
                HttpsURLConnection c=open("/api/files/list","GET");
                if(c.getResponseCode()!=200)throw new IOException("PC file list unavailable, HTTP "+c.getResponseCode());
                JSONArray arr=new JSONArray(body(c.getInputStream()));c.disconnect();
                String[] files=new String[arr.length()];
                for(int i=0;i<files.length;i++)files[i]=arr.getString(i);
                callback.complete(files,null);
            }catch(Exception ex){callback.complete(new String[0],ex.getMessage());}
        });
    }
    void downloadFile(String filename,Consumer<String> feedback){
        work.execute(()->{
            Uri uri=null;
            try{
                HttpsURLConnection c=open("/api/files/download?name="+URLEncoder.encode(filename,"UTF-8"),"GET");
                c.setReadTimeout(90000);
                if(c.getResponseCode()!=200)throw new IOException("Download HTTP "+c.getResponseCode());
                ContentValues values=new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME,filename);
                values.put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");
                values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/RiftLink");
                values.put(MediaStore.Downloads.IS_PENDING,1);
                uri=activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                if(uri==null)throw new IOException("Cannot create Downloads/RiftLink folder");
                try(InputStream in=c.getInputStream();OutputStream out=activity.getContentResolver().openOutputStream(uri)){
                    if(out==null)throw new IOException("Cannot write to phone Downloads");
                    byte[] buf=new byte[64*1024];int n;
                    while((n=in.read(buf))!=-1){out.write(buf,0,n);}
                }
                c.disconnect();
                ContentValues done=new ContentValues();done.put(MediaStore.Downloads.IS_PENDING,0);
                activity.getContentResolver().update(uri,done,null,null);
                feedback.accept("Downloaded "+filename+" to Downloads / RiftLink");
            }catch(Exception ex){
                if(uri!=null)activity.getContentResolver().delete(uri,null,null);
                feedback.accept("Download failed: "+ex.getMessage());
            }
        });
    }
    void stopStream(){
        streaming=false;
        streamEpoch.incrementAndGet();
        HttpsURLConnection old=streamConnection;
        streamConnection=null;
        if(old!=null)old.disconnect();
    }
    void stop(){
        input("up","",0.5f,0.5f);
        input("releaseAllKeys","",0.5f,0.5f);
        input("padReset","",0.5f,0.5f);
        try{Thread.sleep(65);}catch(InterruptedException ignored){}
        running=false;stopAudio();stopStream();
        try{if(inputSocket!=null)inputSocket.close();}catch(Exception ignored){}
        inputWork.shutdownNow();work.shutdownNow();
    }
}
