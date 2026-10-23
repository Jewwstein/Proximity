package app.riftlink.mobile;

import android.app.*;
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
    volatile boolean running=true, streaming=false;
    volatile String token="", fingerprint="";
    volatile SSLSocket inputSocket;
    volatile OutputStream inputOut;
    volatile HttpsURLConnection streamConnection;
    volatile boolean inputConnected=false;
    long lastMove=0;
    interface Discovery{void error(String e);void fingerprint(String fp,boolean known);}
    interface AuthListener{void complete(String token,int width,int height);void error(String e);}
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
                    String old=activity.getPreferences(0).getString("trusted-"+host,"");
                    cb.fingerprint(fp,fp.equals(old));
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
                token=saved;
                JSONObject info=null;
                if(!token.isEmpty()){
                    try{HttpsURLConnection c=open("/api/info","GET");
                        if(c.getResponseCode()==200)info=new JSONObject(body(c.getInputStream()));c.disconnect();
                    }catch(Exception ignored){}
                }
                if(info==null){
                    if(!pin.matches("[0-9]{6}"))throw new Exception("Enter the six-digit pairing PIN shown by RiftLink on your PC.");
                    HttpsURLConnection c=open("/api/pair","POST");c.setDoOutput(true);
                    c.setRequestProperty("Content-Type","application/json");
                    try(OutputStream o=c.getOutputStream()){o.write(new JSONObject().put("pin",pin).toString().getBytes("UTF-8"));}
                    if(c.getResponseCode()!=200)throw new Exception("Pairing rejected (HTTP "+c.getResponseCode()+")");
                    token=new JSONObject(body(c.getInputStream())).getString("token");c.disconnect();
                    c=open("/api/info","GET");if(c.getResponseCode()!=200)throw new Exception("PC info unavailable");
                    info=new JSONObject(body(c.getInputStream()));c.disconnect();
                }
                int w=info.optInt("width"),h=info.optInt("height");
                if(w<=0||h<=0)throw new Exception("PC video encoder is not ready; check RiftLink host.");
                listener.complete(token,w,h);
            }catch(Exception ex){listener.error(ex.getMessage());}
        });
    }
    void connectInputs(){
        inputWork.execute(()->{
            if(!running)return;
            try{
                SSLSocket s=(SSLSocket)context(false).getSocketFactory().createSocket(host,38775);
                s.setTcpNoDelay(true);s.setSoTimeout(5000);s.startHandshake();
                String key=Base64.getEncoder().encodeToString(UUID.randomUUID().toString().substring(0,16).getBytes("UTF-8"));
                String req="GET /api/ws-input?token="+token+" HTTP/1.1\r\nHost: "+host+":38775\r\n"+
                     "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+
                     "\r\nSec-WebSocket-Version: 13\r\n\r\n";
                OutputStream out=s.getOutputStream();out.write(req.getBytes("UTF-8"));out.flush();
                BufferedReader br=new BufferedReader(new InputStreamReader(s.getInputStream()));
                String first=br.readLine();if(first==null||!first.contains("101"))throw new IOException("Input socket rejected: "+first);
                String line;while((line=br.readLine())!=null&&!line.isEmpty()){}
                s.setSoTimeout(0);inputSocket=s;inputOut=out;inputConnected=true;
            }catch(Exception ex){inputConnected=false;activity.runOnUiThread(()->activity.status.setText("Input connection: "+ex.getMessage()));}
        });
    }
    void input(String action,String key,float x,float y){
        if(!running)return;
        if(action.equals("move")){
            long now=SystemClock.elapsedRealtime();
            if(now-lastMove<12)return;lastMove=now;
        }
        JSONObject obj=new JSONObject();
        try{obj.put("a",action);obj.put("x",Math.max(0,Math.min(1,x)));obj.put("y",Math.max(0,Math.min(1,y)));
           if(key!=null&&!key.isEmpty())obj.put("t",key);}catch(Exception ignored){}
        byte[] payload=obj.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        inputWork.execute(()->{
            if(!inputConnected||inputOut==null){if(running)connectInputs();return;}
            try{
                // RFC 6455 masked client text frame
                byte[] mask=new byte[4];new SecureRandom().nextBytes(mask);
                ByteArrayOutputStream f=new ByteArrayOutputStream();
                f.write(0x81);if(payload.length<126){f.write(payload.length|0x80);}
                else {f.write(126|0x80);f.write((payload.length>>8)&255);f.write(payload.length&255);}
                f.write(mask);
                for(int i=0;i<payload.length;i++)f.write(payload[i]^mask[i%4]);
                inputOut.write(f.toByteArray()); inputOut.flush();
            }catch(Exception ex){inputConnected=false;try{inputSocket.close();}catch(Exception ignored){}}
        });
    }
    void startStream(StreamDecoder decoder,Consumer<String> status){
        streaming=true;
        work.execute(()->{
            while(running&&streaming){
                try{
                    HttpsURLConnection c=open("/api/stream?token="+token,"GET");
                    c.setReadTimeout(0);streamConnection=c;
                    if(c.getResponseCode()!=200)throw new IOException("Stream HTTP "+c.getResponseCode());
                    try(DataInputStream in=new DataInputStream(new BufferedInputStream(c.getInputStream(),1024*256))){
                        while(running&&streaming){
                            int len=in.readInt();boolean key=in.readUnsignedByte()==1;long micros=in.readLong();
                            if(len<=0||len>12*1024*1024)throw new IOException("Invalid encoded frame size: "+len);
                            byte[] payload=new byte[len];in.readFully(payload);
                            decoder.frame(payload,key,micros);
                        }
                    }finally{c.disconnect();}
                }catch(Exception ex){
                    if(running&&streaming){
                        status.accept("Video reconnecting: "+ex.getMessage());
                        decoder.reset();
                        try{Thread.sleep(400);}catch(InterruptedException ignored){}
                    }
                }
            }
        });
    }
    void stopStream(){streaming=false;if(streamConnection!=null)streamConnection.disconnect();}
    void stop(){
        running=false;stopStream();input("up","",0.5f,0.5f);
        try{if(inputSocket!=null)inputSocket.close();}catch(Exception ignored){}
        inputWork.shutdownNow();work.shutdownNow();
    }
}
