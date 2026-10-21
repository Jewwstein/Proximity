"""YOLOE visual-prompt test using a real public-domain photographed car key fob.
Positive = same physical key on a cluttered desk at small scale.
Negative = black mouse/controller/microphone shapes WITHOUT the fob.
This tests the visual prompt path, NOT unseen-angle identity generalization.
"""
from PIL import Image, ImageDraw, ImageFilter, ImageOps
import numpy as np
from pathlib import Path
import urllib.request,io,json,time
from ultralytics import YOLOE

out=Path("benchmarks/reports");out.mkdir(parents=True,exist_ok=True)
source="https://upload.wikimedia.org/wikipedia/commons/d/da/Intelligent_key.JPG"
req=urllib.request.Request(source,headers={"User-Agent":"Mozilla/5.0 (research sample; PlainSight vision benchmark)"})
with urllib.request.urlopen(req,timeout=50) as h:
    raw=h.read(2_000_000)
ref=Image.open(io.BytesIO(raw)).convert("RGB")
ref.thumbnail((760,760))
pixels=np.asarray(ref)
# Black key and silver buttons on white background. Mask out bright backdrop.
brightness=pixels.mean(axis=2)
dark=brightness<205
ys,xs=np.where(dark)
if len(xs)<1000:raise RuntimeError("Reference fob photo not segmented correctly")
x0,x1=max(0,int(xs.min())-3),min(ref.width,int(xs.max())+4)
y0,y1=max(0,int(ys.min())-3),min(ref.height,int(ys.max())+4)
box=np.array([[x0,y0,x1,y1]],dtype=np.float32)
cut=ref.crop((x0,y0,x1,y1))
alpha=np.asarray(cut).mean(axis=2)
mask=Image.fromarray(np.uint8(np.clip((214-alpha)*4,0,255)),"L")
# Actual photographed object blended into a dark cluttered tabletop.
def desk(with_key):
    image=Image.new("RGB",(960,720),(94,88,82))
    d=ImageDraw.Draw(image)
    # computer mouse — dark smooth oval, gray center wheel
    d.ellipse((90,170,360,490),fill=(29,30,37),outline=(130,130,133),width=4)
    d.line((225,179,225,333),fill=(80,79,86),width=6)
    d.rounded_rectangle((215,256,239,310),radius=7,fill=(152,151,149))
    # large gaming controller: shoulder area and separated handles
    d.rounded_rectangle((465,90,855,390),radius=66,fill=(20,22,25),outline=(80,83,87),width=3)
    d.ellipse((508,200,622,320),fill=(35,38,43))
    d.ellipse((700,198,816,318),fill=(35,37,42))
    for x,y in [(752,174),(779,193),(748,221),(719,198)]:
        d.ellipse((x-9,y-9,x+9,y+9),fill=(118,119,117))
    # black microphone, loose cable, keyboard corner
    d.rounded_rectangle((65,535,245,690),radius=25,fill=(18,19,21))
    d.line([(260,580),(300,610),(410,595),(455,625)],width=8,fill=(34,36,40))
    d.rectangle((660,510,900,675),fill=(36,39,44))
    for r in range(3):
        for c in range(7):
            x=674+c*31;y=525+r*43
            d.rectangle((x,y,x+24,y+32),fill=(77,79,83))
    target=None
    if with_key:
        # Small key in a noncentral position, at an angle and different scale.
        im=cut.copy()
        mk=mask.copy()
        targetWidth=125
        scale=targetWidth/im.width
        im=im.resize((targetWidth,max(12,int(im.height*scale))),Image.Resampling.LANCZOS)
        mk=mk.resize(im.size,Image.Resampling.LANCZOS)
        rgba=im.convert("RGBA");rgba.putalpha(mk)
        rotated=rgba.rotate(31,expand=True,resample=Image.Resampling.BICUBIC)
        pasteX,pasteY=405,448
        image.paste(rotated,(pasteX,pasteY),rotated)
        target=(pasteX,pasteY,pasteX+rotated.width,pasteY+rotated.height)
    return image,target

positive,actual=desk(True)
negative,_=desk(False)
referencePath=out/"real-fob-reference.jpg"
targetPath=out/"real-fob-desk-positive.jpg"
negativePath=out/"desk-negative-no-keys.jpg"
ref.save(referencePath)
positive.save(targetPath)
negative.save(negativePath)
model=YOLOE("yoloe-26n-seg.pt")
begin=time.perf_counter()
predict=model.predict(positive,refer_image=ref,
    visual_prompts={"bboxes":box,"cls":np.array([0],dtype=np.int32)},
    imgsz=640,conf=.01,verbose=False)[0]
posTime=round(time.perf_counter()-begin,2)
t0=time.perf_counter()
absent=model.predict(negative,imgsz=640,conf=.01,verbose=False)[0]
negTime=round(time.perf_counter()-t0,2)
def parse(result):
    outlist=[]
    for prediction in result.boxes:
        coords=prediction.xyxy[0].tolist()
        outlist.append({"box":[round(x,1) for x in coords],"score":round(float(prediction.conf[0]),3)})
    return sorted(outlist,key=lambda x:-x["score"])[:25]
pos,neg=parse(predict),parse(absent)
def overlap(a,b):
    l=max(a[0],b[0]);t=max(a[1],b[1]);r=min(a[2],b[2]);bottom=min(a[3],b[3])
    area=max(0,r-l)*max(0,bottom-t)
    denominator=(a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-area
    return area/denominator if denominator>0 else 0
bestMatched=sorted([(overlap(p["box"],actual),p["score"]) for p in pos],reverse=True)
found=max([v[0] for v in bestMatched],default=0)
negativeTop=max([x["score"] for x in neg],default=0)
result={"benchmark":"Real public-domain Nissan key fob composited among clutter",
 "reference_source":source,
 "reference_box":box.tolist()[0],
 "target_truth_box":actual,
 "detected_positive":pos,
 "detected_negative":neg,
 "highest_iou_with_key":round(found,4),
 "best_key_prediction":bestMatched[:4],
 "negative_highest_score":negativeTop,
 "first_prediction_seconds":posTime,
 "next_prediction_seconds":negTime,
 "quality_gate":"IOU >= 0.20 AND good candidate score > negative top",
 "passing":bool(found>=.20 and any(iou>=.20 and score>negativeTop for iou,score in bestMatched)),
 "disclaimer":"Composite test not equivalent to a real user key-fob photo or phone FPS; visual prompt may match other same-looking fobs."
}
(out/"yoloe-real-key-fob-benchmark.json").write_text(json.dumps(result,indent=2))
predict.save(filename=str(out/"real-key-fob-annotated.jpg"))
absent.save(filename=str(out/"no-key-negative-annotated.jpg"))
print("KEY_FOB_BENCHMARK",json.dumps({k:v for k,v in result.items() if k not in ("detected_positive","detected_negative")}),flush=True)
# Fail cleanly so bad detectors are not marketed as tested.
assert result["passing"], "FAIL: YOLOE visual prompt did not reliably localize the small real photographed key in clutter."
