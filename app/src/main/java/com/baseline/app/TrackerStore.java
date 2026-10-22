package com.baseline.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** App-private, on-device health journal; no sign-in, analytics, or network. */
public final class TrackerStore extends SQLiteOpenHelper {
    public static final class Entry {
        public String day;
        public int energy=-1,mood=-1,stress=-1,libido=-1,morning=-1,urinary=-1,activity=-1;
        public float sleep=-1f;
        public String symptoms="",note="";
        public Entry(String iso){day=iso;}
        public int filled(){
            int n=0;
            if(energy>=0)n++;if(mood>=0)n++;if(stress>=0)n++;
            if(libido>=0)n++;if(morning>=0)n++;if(urinary>=0)n++;
            if(activity>=0)n++;if(sleep>=0)n++;
            if(!symptoms.trim().isEmpty())n++;
            if(!note.trim().isEmpty())n++;
            return n;
        }
    }
    private static final String TABLE="entries";
    public TrackerStore(Context c){super(c,"baseline_wellness.db",null,1);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE entries (day TEXT PRIMARY KEY NOT NULL, energy INTEGER, mood INTEGER, stress INTEGER, libido INTEGER, morning INTEGER, urinary INTEGER, activity INTEGER, sleep REAL, symptoms TEXT, note TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}
    public void save(Entry e){
        ContentValues v=new ContentValues();
        v.put("day",e.day);v.put("energy",e.energy);v.put("mood",e.mood);
        v.put("stress",e.stress);v.put("libido",e.libido);
        v.put("morning",e.morning);v.put("urinary",e.urinary);
        v.put("activity",e.activity);v.put("sleep",e.sleep);
        v.put("symptoms",e.symptoms);v.put("note",e.note);
        getWritableDatabase().insertWithOnConflict(TABLE,null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static Entry row(Cursor c){
        Entry e=new Entry(c.getString(0));
        e.energy=c.getInt(1);e.mood=c.getInt(2);e.stress=c.getInt(3);
        e.libido=c.getInt(4);e.morning=c.getInt(5);
        e.urinary=c.getInt(6);e.activity=c.getInt(7);
        e.sleep=c.getFloat(8);e.symptoms=c.getString(9);e.note=c.getString(10);
        if(e.symptoms==null)e.symptoms="";
        if(e.note==null)e.note="";
        return e;
    }
    public Entry get(String iso){
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT * FROM entries WHERE day=?",new String[]{iso})){
            if(c.moveToFirst())return row(c);
        }
        return new Entry(iso);
    }
    public List<Entry> all(){
        ArrayList<Entry> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
            "SELECT * FROM entries ORDER BY day DESC LIMIT 5000",null)){
            while(c.moveToNext())rows.add(row(c));
        }
        return rows;
    }
    public void remove(String iso){
        getWritableDatabase().delete(TABLE,"day=?",new String[]{iso});
    }
    public void clear(){getWritableDatabase().delete(TABLE,null,null);}
    /** Excel formula-injection guard for free-text notes and symptom labels. */
    private static String csv(String s){
        if(s==null)return "\"\"";
        String clean=s.replace("\r"," ").replace("\n"," ");
        if(!clean.isEmpty()&&"=+-@\t".indexOf(clean.charAt(0))>=0)clean="'"+clean;
        return "\""+clean.replace("\"","\"\"")+"\"";
    }
    public String csvExport(){
        StringBuilder b=new StringBuilder(
            "date,sleep_hours,energy_1_5,mood_1_5,stress_1_5,libido_1_5,morning_erection,urinary_change,activity,symptoms,notes\n");
        for(Entry e:all()){
            b.append(e.day).append(',').append(number(e.sleep)).append(',')
                .append(number(e.energy)).append(',').append(number(e.mood)).append(',')
                .append(number(e.stress)).append(',').append(number(e.libido)).append(',')
                .append(option(e.morning)).append(',').append(option(e.urinary)).append(',')
                .append(option(e.activity)).append(',').append(csv(e.symptoms)).append(',')
                .append(csv(e.note)).append('\n');
        }
        return b.toString();
    }
    private static String number(int value){return value<0?"":String.valueOf(value);}
    private static String number(float value){return value<0?"":String.valueOf(value);}
    private static String option(int value){return value<0?"":value==1?"yes":"no";}
}