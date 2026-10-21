package com.plainsight.finder;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.Locale;

public final class OfflineTextFinder implements AutoCloseable {
    public interface Callback {
        void onResult(RectF bounds);
        void onError(String error);
    }
    private final TextRecognizer recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    public static String searchTerm(String q) {
        String t=q.toLowerCase(Locale.ROOT).trim()
            .replaceFirst("^(please\\s+)?(find|locate|where is|look for|show me)\\s+","")
            .replaceFirst("^(the|a|an|my)\\s+","").trim();
        t=t.replaceFirst("^(number|digit)\\s+","");
        String[] words={"zero","one","two","three","four","five","six","seven","eight","nine"};
        for(int i=0;i<words.length;i++)if(t.equals(words[i]))return String.valueOf(i);
        if(t.matches("[0-9]"))return t;
        // A short quoted word or an explicit written-text target is OCR, not general object search.
        if(t.startsWith("text ")||t.startsWith("word ")||t.startsWith("letter "))
            return t.replaceFirst("^(text|word|letter)\\s+","");
        return null;
    }
    public void scan(Bitmap source,String target,Callback callback) {
        recognizer.process(InputImage.fromBitmap(source,0))
            .addOnSuccessListener(output->{
                RectF best=null;float area=0;
                for(Text.TextBlock block:output.getTextBlocks()){
                    for(Text.Line line:block.getLines()){
                        for(Text.Element element:line.getElements()){
                            String s=element.getText().trim().toLowerCase(Locale.ROOT);
                            Rect box=element.getBoundingBox();
                            if(box==null || s.isEmpty())continue;
                            int charPos=s.indexOf(target.toLowerCase(Locale.ROOT));
                            if(charPos<0)continue;
                            float l=box.left,t=box.top,r=box.right,b=box.bottom;
                            if(s.length()>1 && target.length()==1){
                                // Approximate the character's location within a digital time or word.
                                float cw=(r-l)/s.length();
                                l+=cw*charPos;r=l+cw;
                            }
                            RectF normalized=new RectF(
                                Math.max(0,l/source.getWidth()),
                                Math.max(0,t/source.getHeight()),
                                Math.min(1,r/source.getWidth()),
                                Math.min(1,b/source.getHeight()));
                            float a=normalized.width()*normalized.height();
                            if(best==null||a>area){best=normalized;area=a;}
                        }
                    }
                }
                callback.onResult(best);
            })
            .addOnFailureListener(e->callback.onError(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));
    }
    @Override public void close(){recognizer.close();}
}
