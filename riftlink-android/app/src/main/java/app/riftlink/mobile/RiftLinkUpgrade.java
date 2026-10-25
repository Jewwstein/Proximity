package app.riftlink.mobile;

import android.app.AlertDialog;
import android.os.Build;
import android.view.View;
import java.util.*;
import org.json.*;

/** V2.2 additive UI and pairing. Existing V2.2 audio, decoder, controls and network
 * dataplane are deliberately unchanged. */
final class RiftLinkUpgrade {
  final MainActivity a;
  boolean gameMode=false;
  Runnable telemetryTick;
  RiftLinkUpgrade(MainActivity a){this.a=a;}

  void discover(boolean game){
    gameMode=game;
    a.status.setText("Searching for RiftLink PCs…");
    new Thread(()->{
      Map<String,RiftLinkExtra.PC> pcs=new LinkedHashMap<>();
      for(String ip:a.getPreferences(0).getStringSet("rl-pcs",Collections.<String>emptySet()))
        pcs.put(ip,new RiftLinkExtra.PC(
          a.getPreferences(0).getString("rl-name-"+ip,"RiftLink PC"),
          ip,a.getPreferences(0).getString("rl-fingerprint-"+ip,"")));
      try{for(RiftLinkExtra.PC p:RiftLinkExtra.findPCs())pcs.put(p.ip,p);}
      catch(Exception ex){android.util.Log.w("RiftLink","Discovery limited",ex);}
      a.runOnUiThread(()->{
        if(a.isFinishing())return;
        if(pcs.isEmpty()){
          new AlertDialog.Builder(a).setTitle("No PCs discovered yet")
            .setMessage("Check that the updated RiftLink host is running on your PC and both devices are on the same Wi-Fi. Your original manual host/PIN connection is still available below.")
            .setPositiveButton("Scan again",(d,w)->discover(game))
            .setNegativeButton("Manual connection",null).show();
          return;
        }
        ArrayList<RiftLinkExtra.PC> list=new ArrayList<>(pcs.values());
        String[] labels=new String[list.size()];
        for(int i=0;i<labels.length;i++)labels[i]=list.get(i).name+"   "+list.get(i).ip;
        new AlertDialog.Builder(a).setTitle(game?"GAME MODE / CHOOSE PC":"DESKTOP MODE / CHOOSE PC")
          .setItems(labels,(d,i)->connect(list.get(i)))
          .setPositiveButton("Scan again",(d,w)->discover(game))
          .setNegativeButton("Cancel",null).show();
      });
    },"RiftLink-local-discovery").start();
  }

  void connect(RiftLinkExtra.PC pc){
    a.hostBox.setText(pc.ip);
    a.getPreferences(0).edit().putString("host",pc.ip).apply();
    if(a.net!=null)a.net.stop();
    a.net=new RiftNet(a,pc.ip);
    final RiftNet n=a.net;
    a.status.setText("Verifying PC certificate…");
    n.discoverFingerprint(new RiftNet.Discovery(){
      public void error(String error){a.runOnUiThread(()->a.toast("Cannot connect: "+error));}
      public void fingerprint(String fp,boolean known){
        a.runOnUiThread(()->{
          if(a.net!=n)return;
          if(!pc.fingerprint.isEmpty()&&!pc.fingerprint.equalsIgnoreCase(fp)){
            new AlertDialog.Builder(a).setTitle("PC identity mismatch")
              .setMessage("The PC certificate does not match its discovery announcement. Nothing was paired.")
              .setPositiveButton("OK",null).show();return;
          }
          Runnable trust=()->{
            n.pin(fp);
            Set<String> ips=new HashSet<>(a.getPreferences(0).getStringSet("rl-pcs",Collections.<String>emptySet()));
            ips.add(pc.ip);
            a.getPreferences(0).edit().putStringSet("rl-pcs",ips)
              .putString("rl-fingerprint-"+pc.ip,fp)
              .putString("rl-name-"+pc.ip,pc.name).apply();
            authorize(n);
          };
          if(known||!pc.fingerprint.isEmpty()){trust.run();return;}
          new AlertDialog.Builder(a).setTitle("Trust this PC?")
            .setMessage("Host: "+pc.ip+"\nSHA-256 certificate:\n"+fp+
              "\n\nConfirm this is your PC. Its identity will be remembered.")
            .setPositiveButton("Trust and request approval",(d,w)->trust.run())
            .setNegativeButton("Cancel",null).show();
        });
      }
    });
  }

  void authorize(RiftNet n){
    String token=a.getPreferences(0).getString("token-cert-"+n.fingerprint,"");
    if(token.isEmpty())token=a.getPreferences(0).getString("token-"+n.host,"");
    if(!token.isEmpty()){a.startAuthorizedConnection();return;}
    String id=a.getPreferences(0).getString("autopair-device-id","");
    if(id.isEmpty()){
      id=UUID.randomUUID().toString();
      a.getPreferences(0).edit().putString("autopair-device-id",id).apply();
    }
    final String deviceId=id;
    a.status.setText("Awaiting approval in Windows RiftLink Studio…");
    n.work.execute(()->{
      try{
        String approved=RiftLinkExtra.approve(n,deviceId,Build.MANUFACTURER+" "+Build.MODEL,
          msg->a.runOnUiThread(()->a.status.setText(msg)));
        a.runOnUiThread(()->{
          if(a.net!=n)return;
          a.getPreferences(0).edit().putString("token-"+n.host,approved)
            .putString("token-cert-"+n.fingerprint,approved).apply();
          a.startAuthorizedConnection();  // original V2.2 transport and decoder
        });
      }catch(Exception ex){a.runOnUiThread(()->{
        if(a.net!=n)return;
        a.status.setText("Approval: "+ex.getMessage());
        new AlertDialog.Builder(a).setTitle("Pairing could not finish")
          .setMessage(ex.getMessage()+"\n\nThe original V2.2 stream and controller are unchanged.")
          .setPositiveButton("Retry",(d,w)->authorize(n))
          .setNegativeButton("Cancel",null).show();
      });}
    });
  }

  void afterConnected(){
    if(telemetryTick!=null)a.ui.removeCallbacks(telemetryTick);
    telemetryTick=new Runnable(){public void run(){
      final RiftNet n=a.net;
      if(n==null||!n.running||n.token.isEmpty())return;
      n.work.execute(()->{try{RiftLinkExtra.sendPhoneStats(n,a,gameMode);}catch(Exception ignored){}});
      a.ui.postDelayed(this,30000);
    }};
    a.ui.postDelayed(telemetryTick,1200);
    if(gameMode)a.ui.postDelayed(this::showGameLibrary,650);
  }

  void stop(){
    if(telemetryTick!=null)a.ui.removeCallbacks(telemetryTick);
    telemetryTick=null;
  }

  void desktop(){
    gameMode=false;
    a.controls.cancelAll();
    a.controls.game=false;
    a.controls.invalidate();
    a.toast("Desktop Mode — original V2.2 mouse and keyboard");
    if(a.menuScroller!=null)a.menuScroller.setVisibility(View.GONE);
  }

  void showGameLibrary(){
    if(a.menuScroller!=null)a.menuScroller.setVisibility(View.GONE);
    final RiftNet n=a.net;
    if(n==null||n.token.isEmpty()){discover(true);return;}
    n.work.execute(()->{
      try{
        JSONArray games=RiftLinkExtra.games(n);
        a.runOnUiThread(()->{
          if(n!=a.net)return;
          if(games==null||games.length()==0){
            new AlertDialog.Builder(a).setTitle("No configured PC games")
              .setMessage("Add games in RiftLink Studio > Game library on your PC. The original desktop stream remains available.")
              .setPositiveButton("Desktop Mode",(d,w)->desktop()).show();return;
          }
          String[] names=new String[games.length()];
          for(int i=0;i<names.length;i++){
            JSONObject item=games.optJSONObject(i);
            names[i]=item==null?"Game":item.optString("title","Game")+
              (item.optBoolean("available",true)?"":" (unavailable)");
          }
          new AlertDialog.Builder(a).setTitle("RiftLink / Game Mode")
            .setItems(names,(d,index)->{
              JSONObject item=games.optJSONObject(index);
              if(item!=null)launch(item.optString("id",""),item.optString("title","Game"));
            })
            .setPositiveButton("Desktop Mode",(d,w)->desktop())
            .setNegativeButton("Cancel",null).show();
        });
      }catch(Exception ex){a.runOnUiThread(()->new AlertDialog.Builder(a)
        .setTitle("Game library unavailable")
        .setMessage(ex.getMessage()+"\n\nThe original V2.2 desktop stream is still available.")
        .setPositiveButton("Desktop Mode",(d,w)->desktop()).show());}
    });
  }

  void launch(String id,String title){
    final RiftNet n=a.net;
    if(n==null)return;
    n.work.execute(()->{
      try{
        RiftLinkExtra.launch(n,id);
        a.runOnUiThread(()->{
          gameMode=true;
          a.controls.game=true;
          // Deliberately retain user-adjusted FPS layout and existing control mappings.
          a.controls.invalidate();
          a.status.setText("RiftLink Game Mode • "+title);
          a.toast("Launching "+title+" on your PC");
        });
      }catch(Exception ex){a.runOnUiThread(()->a.toast("Game launch: "+ex.getMessage()));}
    });
  }
}
