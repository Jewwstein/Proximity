package com.proximity.app;
import android.Manifest; import android.app.*; import android.bluetooth.*; import android.bluetooth.le.*; import android.content.*; import android.content.pm.PackageManager; import android.graphics.*; import android.os.*; import android.view.*; import java.util.*;
public class MainActivity extends Activity {
 RadarView radar; BluetoothLeScanner scanner; final Map<String,D> ds=new LinkedHashMap<>();
 public void onCreate(Bundle b){super.onCreate(b);radar=new RadarView(this);setContentView(radar);if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},7);else scan();}
 public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==7)scan();}
 void scan(){BluetoothManager m=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);if(m==null||m.getAdapter()==null||!m.getAdapter().isEnabled()){radar.s="BLUETOOTH OFF";radar.invalidate();return;}scanner=m.getAdapter().getBluetoothLeScanner();if(scanner==null)return;try{scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),cb);radar.s="LIVE SCAN";}catch(SecurityException e){radar.s="PERMISSION NEEDED";}radar.invalidate();}
 final ScanCallback cb=new ScanCallback(){public void onScanResult(int t,ScanResult r){String id;try{id=r.getDevice().getAddress();}catch(Exception e){id="d"+r.hashCode();}String n=r.getScanRecord()==null?null:r.getScanRecord().getDeviceName();if(n==null||n.isEmpty())n="Nearby device";D d=ds.get(id);if(d==null){d=new D(id,n,r.getRssi());ds.put(id,d);}d.n=n;d.r=(int)(d.r*.65+r.getRssi()*.35);d.t=System.currentTimeMillis();if(ds.size()>40)ds.remove(ds.keySet().iterator().next());radar.invalidate();}};
 protected void onDestroy(){super.onDestroy();try{if(scanner!=null)scanner.stopScan(cb);}catch(Exception e){}}
 class D{String id,n;int r;long t;D(String i,String n,int r){id=i;this.n=n;this.r=r;t=System.currentTimeMillis();}double m(){return Math.pow(10.0,(-59-r)/22.0);}}
 class RadarView extends View{
  Paint p=new Paint(3),tx=new Paint(3); String s="STARTING…"; final int bg=Color.rgb(7,17,15),card=Color.rgb(12,29,25),edge=Color.rgb(32,67,57),green=Color.rgb(101,230,180),muted=Color.rgb(153,180,171);
  RadarView(Context c){super(c);setBackgroundColor(bg);}
  void rr(Canvas c,float l,float t,float r,float b,float rad,int color){p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawRoundRect(l,t,r,b,rad,rad,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setColor(edge);c.drawRoundRect(l,t,r,b,rad,rad,p);}
  void label(Canvas c,String a,float x,float y,float size,int color,boolean bold){tx.setTextSize(size);tx.setColor(color);tx.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);c.drawText(a,x,y,tx);}
  protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),pad=24;
   label(c,"PROXIMITY",pad,54,36,Color.WHITE,true); label(c,"Nearby signal radar",pad,80,15,muted,false);
   rr(c,pad,98,w-pad,154,18,card); p.setStyle(Paint.Style.FILL);p.setColor(green);c.drawCircle(pad+25,126,7,p);label(c,s,pad+42,132,16,green,true);label(c,ds.size()+" signals",w-pad-105,132,15,Color.WHITE,false);

   float radarTop=174, radarBottom=Math.min(h*.55f,780), cx=w/2, cy=(radarTop+radarBottom)/2, R=Math.min(w*.43f,(radarBottom-radarTop)*.43f);
   p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(8,23,19));c.drawCircle(cx,cy,R+14,p);
   p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(edge);for(int j=1;j<=4;j++)c.drawCircle(cx,cy,R*j/4,p);c.drawLine(cx-R,cy,cx+R,cy,p);c.drawLine(cx,cy-R,cx,cy+R,p);
   label(c,"15 ft",cx+7,cy-R/4+18,13,muted,false);label(c,"30 ft",cx+7,cy-R/2+18,13,muted,false);label(c,"60 ft",cx+7,cy-R*3/4+18,13,muted,false);
   p.setStyle(Paint.Style.FILL);p.setColor(green);c.drawCircle(cx,cy,13,p);label(c,"YOU",cx-16,cy+38,13,Color.WHITE,true);
   long now=System.currentTimeMillis();ArrayList<D> l=new ArrayList<>();for(D d:ds.values())if(now-d.t<15000)l.add(d);l.sort((a,b)->Integer.compare(b.r,a.r));int k=0;
   for(D d:l){float rr=(float)Math.min(R,Math.max(30,R*Math.min(18,d.m())/18));double a=k++*2.399963;float x=cx+(float)Math.cos(a)*rr,y=cy+(float)Math.sin(a)*rr;p.setColor(green);c.drawCircle(x,y,8,p);}

   float sy=radarBottom+20, capH=118, bottomPad=24, available=h-sy-capH-bottomPad-18; float sigH=Math.max(230,available);
   rr(c,pad,sy,w-pad,sy+sigH,22,card);label(c,"NEARBY SIGNALS",pad+20,sy+34,19,Color.WHITE,true);label(c,"Strongest first",w-pad-116,sy+34,13,muted,false);
   float y=sy+70;int shown=0;for(D d:l){if(shown++>=5||y>sy+sigH-35)break;p.setStyle(Paint.Style.FILL);p.setColor(green);c.drawCircle(pad+28,y-5,6,p);label(c,d.n,pad+48,y,16,Color.WHITE,true);String dist=String.format(Locale.US,"~%.1f ft",d.m()*3.28084);label(c,d.r+" dBm",w-pad-165,y,14,muted,false);label(c,dist,w-pad-82,y,14,green,true);if(y+18<sy+sigH-12){p.setColor(edge);c.drawRect(pad+20,y+22,w-pad-20,y+23,p);}y+=48;}
   if(l.isEmpty())label(c,"Searching for Bluetooth LE advertisements…",pad+20,sy+82,15,muted,false);

   float cy2=sy+sigH+18;rr(c,pad,cy2,w-pad,Math.min(h-bottomPad,cy2+capH),22,card);label(c,"PHONE CAPABILITIES",pad+20,cy2+32,18,Color.WHITE,true);
   PackageManager pm=getPackageManager();boolean ble=pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),uwb=Build.VERSION.SDK_INT>=31&&pm.hasSystemFeature("android.hardware.uwb"),rtt=Build.VERSION.SDK_INT>=28&&pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT);
   label(c,"BLE  "+yn(ble),pad+20,cy2+62,14,ble?green:muted,true);label(c,"UWB  "+yn(uwb),pad+135,cy2+62,14,uwb?green:muted,true);label(c,"Wi-Fi RTT  "+yn(rtt),pad+245,cy2+62,14,rtt?green:muted,true);label(c,"Distance is estimated • dot angle is not direction",pad+20,cy2+91,12,muted,false);
  }
  String yn(boolean b){return b?"YES":"NO";}
 }
}