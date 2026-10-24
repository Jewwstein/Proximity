package app.riftlink.mobile;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;
import org.json.*;
import java.io.*;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated phone file bridge. No access beyond a user-picked SAF folder. */
final class PhoneBridge {
    static final int PICK_SHARED_TREE=1299;
    final MainActivity activity;
    final RiftNet net;
    final AtomicBoolean active=new AtomicBoolean(false);
    volatile Uri sharedTree;
    Thread worker;
    PhoneBridge(MainActivity a,RiftNet n){
        activity=a;net=n;
        String saved=a.getPreferences(0).getString("shared-tree","");
        if(!saved.isEmpty())try{sharedTree=Uri.parse(saved);}catch(Exception ignored){}
    }
    void pickSharedFolder(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION|
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        activity.startActivityForResult(i,PICK_SHARED_TREE);
    }
    void selectedTree(Intent result){
        if(result==null||result.getData()==null)return;
        Uri tree=result.getData();
        int mode=result.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try{
            activity.getContentResolver().takePersistableUriPermission(tree,mode);
            sharedTree=tree;
            activity.getPreferences(0).edit().putString("shared-tree",tree.toString()).apply();
            activity.toast("Phone folder shared with the Windows dashboard");
        }catch(Exception ex){activity.toast("Phone folder permission failed: "+ex.getMessage());}
    }
    void start(){
        if(!active.compareAndSet(false,true))return;
        worker=new Thread(()->{
            while(active.get()&&net.running){
                try{
                    report();
                    poll();
                }catch(Exception ignored){}
                try{Thread.sleep(3200);}catch(InterruptedException ignored){break;}
            }
        },"RiftLink-PhoneBridge");
        worker.setDaemon(true);
        worker.start();
    }
    void stop(){
        active.set(false);
        if(worker!=null)worker.interrupt();
    }
    JSONObject buildReport()throws Exception {
        JSONObject r=new JSONObject();
        r.put("name",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL);
        android.os.BatteryManager battery=(android.os.BatteryManager)activity.getSystemService(Context.BATTERY_SERVICE);
        int pct=battery!=null?battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY):-1;
        r.put("battery",pct);
        android.app.ActivityManager mgr=(android.app.ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mem=new android.app.ActivityManager.MemoryInfo();
        mgr.getMemoryInfo(mem);
        r.put("memoryTotal",mem.totalMem);r.put("memoryFree",mem.availMem);
        android.os.StatFs st=new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
        r.put("storageTotal",st.getTotalBytes());r.put("storageFree",st.getAvailableBytes());
        JSONArray files=new JSONArray();
        if(sharedTree!=null){
            r.put("sharedFolder","User-selected phone folder");
            LinkedList<String[]> queue=new LinkedList<>();
            String root=DocumentsContract.getTreeDocumentId(sharedTree);
            queue.add(new String[]{root,"", "0"});
            int scanned=0;
            String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE};
            while(!queue.isEmpty()&&files.length()<220&&scanned++<250){
                String[] parent=queue.removeFirst();
                int depth=Integer.parseInt(parent[2]);
                Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(sharedTree,parent[0]);
                try(Cursor cursor=activity.getContentResolver().query(children,projection,null,null,null)){
                    if(cursor==null)continue;
                    while(cursor.moveToNext()&&files.length()<220){
                        String id=cursor.getString(0),name=cursor.getString(1),type=cursor.getString(2);
                        long size=cursor.isNull(3)?0:cursor.getLong(3);
                        if(id==null||name==null)continue;
                        boolean folder=DocumentsContract.Document.MIME_TYPE_DIR.equals(type);
                        String path=parent[1].isEmpty()?name:parent[1]+"/"+name;
                        JSONObject f=new JSONObject();
                        f.put("id",id);f.put("name",name);f.put("folder",folder);
                        f.put("size",size);f.put("path",path);
                        files.put(f);
                        if(folder&&depth<3&&queue.size()<60)queue.add(new String[]{id,path,""+(depth+1)});
                    }
                }catch(Exception ignored){}
            }
        }else r.put("sharedFolder","Not shared — choose a folder in RiftLink on phone");
        r.put("files",files);
        return r;
    }
    void report()throws Exception{
        javax.net.ssl.HttpsURLConnection c=net.open("/api/phone/report","POST");
        try{
            c.setDoOutput(true);c.setConnectTimeout(2500);c.setReadTimeout(3500);
            c.setRequestProperty("Content-Type","application/json");
            byte[] bytes=buildReport().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            c.getResponseCode();
        }finally{c.disconnect();}
    }
    void poll()throws Exception{
        javax.net.ssl.HttpsURLConnection c=net.open("/api/phone/tasks","GET");
        JSONArray tasks;
        try{
            c.setConnectTimeout(2500);c.setReadTimeout(4000);
            if(c.getResponseCode()!=200)return;
            tasks=new JSONArray(RiftNet.body(c.getInputStream()));
        }finally{c.disconnect();}
        for(int i=0;i<tasks.length();i++){
            JSONObject task=tasks.getJSONObject(i);
            String action=task.optString("action"),id=task.optString("id");
            try{
                if(action.equals("pull"))uploadRequestedFile(task.optString("documentId"),id);
                if(action.equals("push"))receiveRequestedFile(task.optString("name"));
            }catch(Exception error){
                activity.runOnUiThread(()->activity.status.setText("Phone transfer: "+error.getMessage()));
            }
        }
    }
    void uploadRequestedFile(String documentId,String task)throws Exception{
        if(sharedTree==null||documentId==null||documentId.isEmpty())return;
        Uri uri=DocumentsContract.buildDocumentUriUsingTree(sharedTree,documentId);
        String name="phone-file";
        try(Cursor c=activity.getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())name=c.getString(0);
        }
        javax.net.ssl.HttpsURLConnection c=net.open("/api/phone/pull?id="+
            URLEncoder.encode(task,"UTF-8")+"&name="+URLEncoder.encode(name,"UTF-8"),"POST");
        try{
            c.setDoOutput(true);c.setChunkedStreamingMode(65536);c.setReadTimeout(90000);
            c.setRequestProperty("Content-Type","application/octet-stream");
            try(InputStream in=activity.getContentResolver().openInputStream(uri);
                OutputStream out=c.getOutputStream()){
                if(in==null)throw new IOException("File not accessible");
                byte[] buffer=new byte[65536];long bytes=0;int n;
                while((n=in.read(buffer))!=-1){
                    bytes+=n;if(bytes>512L*1024*1024)throw new IOException("Maximum 512 MB");
                    out.write(buffer,0,n);
                }
            }
            if(c.getResponseCode()!=200)throw new IOException("Windows rejected phone file");
        }finally{c.disconnect();}
    }
    void receiveRequestedFile(String filename)throws Exception{
        if(filename==null||filename.isEmpty())return;
        if(sharedTree==null){
            net.downloadFile(filename,message->activity.runOnUiThread(()->activity.status.setText(message)));
            return;
        }
        javax.net.ssl.HttpsURLConnection c=net.open("/api/files/download?name="+
            URLEncoder.encode(filename,"UTF-8"),"GET");
        Uri created=null;
        try{
            c.setReadTimeout(90000);
            if(c.getResponseCode()!=200)throw new IOException("PC file unavailable");
            String root=DocumentsContract.getTreeDocumentId(sharedTree);
            Uri rootDoc=DocumentsContract.buildDocumentUriUsingTree(sharedTree,root);
            String type=MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                filename.contains(".")?filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"");
            created=DocumentsContract.createDocument(activity.getContentResolver(),rootDoc,
                type==null?"application/octet-stream":type,filename);
            if(created==null)throw new IOException("Cannot create file in shared phone folder");
            try(InputStream in=c.getInputStream();OutputStream out=activity.getContentResolver().openOutputStream(created)){
                if(out==null)throw new IOException("Phone storage unavailable");
                byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
        }catch(Exception e){
            if(created!=null)try{DocumentsContract.deleteDocument(activity.getContentResolver(),created);}catch(Exception ignored){}
            throw e;
        }finally{c.disconnect();}
    }
}