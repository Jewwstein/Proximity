package app.riftlink.mobile;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.app.ActivityManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/**
 * Optional control-plane features for the original RiftLink 2.2 streaming client.
 * Does NOT implement video, audio, mouse, keyboard, or gamepad streaming.
 */
final class RiftLinkExtra {
  static final class PC {
    final String name, ip, fingerprint;
    PC(String name, String ip, String fingerprint) {
      this.name=name;this.ip=ip;this.fingerprint=fingerprint;
    }
  }

  static List<PC> findPCs() throws Exception {
    byte[] random=new byte[16];new SecureRandom().nextBytes(random);
    StringBuilder hex=new StringBuilder();
    for(byte b:random)hex.append(String.format(Locale.US,"%02x",b&255));
    String nonce=hex.toString();
    Set<String> targets=new LinkedHashSet<>();
    targets.add("255.255.255.255");
    Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
    while(interfaces!=null&&interfaces.hasMoreElements()) {
      NetworkInterface ni=interfaces.nextElement();
      if(!ni.isUp()||ni.isLoopback())continue;
      for(InterfaceAddress ia:ni.getInterfaceAddresses())
        if(ia.getBroadcast()!=null)targets.add(ia.getBroadcast().getHostAddress());
    }
    Map<String,PC> found=new LinkedHashMap<>();
    try(DatagramSocket socket=new DatagramSocket()) {
      socket.setBroadcast(true);
      socket.setSoTimeout(260);
      byte[] request=("RIFTLINK_DISCOVER_V2 "+nonce).getBytes("UTF-8");
      for(String target:targets)try {
        socket.send(new DatagramPacket(request,request.length,InetAddress.getByName(target),38776));
      }catch(Exception ignored){}
      long until=System.currentTimeMillis()+2300;
      while(System.currentTimeMillis()<until) {
        byte[] buf=new byte[1600];DatagramPacket packet=new DatagramPacket(buf,buf.length);
        try {
          socket.receive(packet);
          JSONObject data=new JSONObject(new String(packet.getData(),packet.getOffset(),packet.getLength(),"UTF-8"));
          if(!nonce.equals(data.optString("nonce"))||!"RIFTLINK_HOST_V2".equals(data.optString("type")))continue;
          String fp=data.optString("fingerprint","").toUpperCase(Locale.US);
          if(!fp.matches("[A-F0-9]{64}")||data.optInt("port",38775)!=38775)continue;
          String name=data.optString("name","RiftLink PC");
          found.put(fp,new PC(name.length()>60?name.substring(0,60):name,packet.getAddress().getHostAddress(),fp));
        }catch(SocketTimeoutException timeout){}catch(Exception invalid){}
      }
    }
    return new ArrayList<>(found.values());
  }

  private static JSONObject json(RiftNet net,String path,String method,JSONObject payload) throws Exception {
    HttpsURLConnection c=net.open(path,method);
    try {
      if(payload!=null){
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json");
        try(OutputStream out=c.getOutputStream()) {
          out.write(payload.toString().getBytes("UTF-8"));
        }
      }
      int code=c.getResponseCode();
      String type=c.getContentType()==null?"":c.getContentType().toLowerCase(Locale.US);
      if(code==204)return new JSONObject();
      if(code!=200)throw new java.io.IOException(path+": HTTP "+code);
      if(!type.contains("json"))throw new java.io.IOException("Old RiftLink host: approval/game API not available");
      try(InputStream in=c.getInputStream()) {return new JSONObject(RiftNet.body(in));}
    }finally{c.disconnect();}
  }

  static String approve(RiftNet net,String deviceId,String model,java.util.function.Consumer<String> progress) throws Exception {
    JSONObject capabilities=json(net,"/api/pair/capabilities","GET",null);
    if(!"v2".equals(capabilities.optString("approval"))||!"RiftLink".equals(capabilities.optString("service")))
      throw new java.io.IOException("Update the RiftLink PC host to enable no-PIN approval");
    JSONObject reply=json(net,"/api/pair/request","POST",new JSONObject()
      .put("deviceId",deviceId).put("name",model));
    String id=reply.getString("id"),secret=reply.getString("secret");
    if(id.isEmpty()||secret.isEmpty())throw new java.io.IOException("PC did not create an approval request");
    progress.accept("Approval requested. Approve this phone in RiftLink Studio > Connections.");
    for(int i=0;i<100;i++){
      if(!net.running)throw new java.io.IOException("Connection cancelled");
      Thread.sleep(1700);
      JSONObject poll=json(net,"/api/pair/status","POST",new JSONObject().put("id",id).put("secret",secret));
      String status=poll.optString("status");
      if("approved".equals(status)){
        String token=poll.getString("token");
        if(!token.matches("[a-fA-F0-9]{64}"))throw new java.io.IOException("PC returned invalid credential");
        return token;
      }
      if("denied".equals(status))throw new java.io.IOException("Approval declined on the PC");
    }
    throw new java.io.IOException("Approval timed out. Open Connections on your PC and try again.");
  }

  static JSONArray games(RiftNet net) throws Exception {
    return json(net,"/api/games","GET",null).optJSONArray("games");
  }

  static void launch(RiftNet net,String id) throws Exception {
    json(net,"/api/games/launch","POST",new JSONObject().put("id",id));
  }

  static void sendPhoneStats(RiftNet net,Context context,boolean game) throws Exception {
    ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
    ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();am.getMemoryInfo(memory);
    StatFs fs=new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
    Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    int b=battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
    int scale=battery==null?100:battery.getIntExtra(BatteryManager.EXTRA_SCALE,100);
    int state=battery==null?0:battery.getIntExtra(BatteryManager.EXTRA_STATUS,0);
    JSONObject payload=new JSONObject();
    payload.put("model",Build.MANUFACTURER+" "+Build.MODEL);
    payload.put("android",Build.VERSION.RELEASE);
    payload.put("battery",b<0?-1:Math.max(0,Math.min(100,(100*b)/Math.max(1,scale))));
    payload.put("charging",state==BatteryManager.BATTERY_STATUS_CHARGING||state==BatteryManager.BATTERY_STATUS_FULL);
    payload.put("memoryMB",memory.totalMem/(1024L*1024));
    payload.put("availableMemoryMB",memory.availMem/(1024L*1024));
    payload.put("storageMB",fs.getTotalBytes()/(1024L*1024));
    payload.put("availableStorageMB",fs.getAvailableBytes()/(1024L*1024));
    payload.put("mode",game?"game":"desktop");
    json(net,"/api/device/phone","POST",payload);
  }
}
