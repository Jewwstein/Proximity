"""
Plain Sight image-guided model gate.
Uses YOLOE VISUAL EXAMPLE PROMPTS, not class names, generic detector
scores, or color-only cosine similarity.
Results are written to benchmarks/reports/yoloe-visual-results.json.
A clean negative image MUST NOT yield a confident match.
"""
import json
import os
import time
from pathlib import Path
import urllib.request
from io import BytesIO
import numpy as np
from PIL import Image, ImageOps, ImageDraw, ImageFilter
from ultralytics import YOLOE

ROOT=Path("benchmarks")
ROOT.mkdir(parents=True,exist_ok=True)
REPORTS=ROOT/"reports";REPORTS.mkdir(parents=True,exist_ok=True)
def download(url,name):
    path=ROOT/name
    if not path.exists():
        req=urllib.request.Request(url,headers={"User-Agent":"PlainSightBench/0.1 (research example image benchmark)"})
        with urllib.request.urlopen(req, timeout=35) as response:
            data=response.read(8_000_000)
        path.write_bytes(data)
    return str(path)

# This baseline is grounded in the exact official example supplied in Ultralytics docs.
bus=download("https://ultralytics.com/images/bus.jpg","reference-bus.jpg")
zidane=download("https://ultralytics.com/images/zidane.jpg","different-person-scene.jpg")
reference=Image.open(bus).convert("RGB")
target=Image.open(zidane).convert("RGB")

visual_prompts={"bboxes":np.array([[221.52,405.8,344.98,857.54]],dtype=np.float32),
                "cls":np.array([0],dtype=np.int32)}
model=YOLOE("yoloe-26n-seg.pt")
print("Running official cross-image visual example benchmark ...",flush=True)
begin=time.perf_counter()
positive=model.predict(target,refer_image=reference,
    visual_prompts=visual_prompts,imgsz=480,conf=.06,verbose=False)
warmup_seconds=round(time.perf_counter()-begin,3)
print("First visual prompt infer secs",warmup_seconds,"boxes",len(positive[0].boxes),flush=True)
# Per docs model's visual prompt profile is carried forward for later calls.
t0=time.perf_counter()
pos_again=model.predict(target,imgsz=480,conf=.06,verbose=False)
latency=round(time.perf_counter()-t0,3)
print("Followup CPU seconds",latency,"boxes",len(pos_again[0].boxes),flush=True)

# A no-person negative. Distinct colored shapes / object silhouettes on a table.
negative=Image.new("RGB",target.size,(204,203,201))
draw=ImageDraw.Draw(negative)
for i in range(8):
    x=80+(i%4)*240; y=70+(i//4)*320
    draw.rounded_rectangle((x,y,x+150,y+90),radius=24,fill=(36+10*i,38+6*i,48+5*i))
    draw.ellipse((x+27,y+15,x+125,y+75),outline=(172,174,172),width=7)
t1=time.perf_counter()
neg_result=model.predict(negative,imgsz=480,conf=.06,verbose=False)
negative_latency=round(time.perf_counter()-t1,3)
print("Negative scene boxes",len(neg_result[0].boxes),"seconds",negative_latency,flush=True)
def summarize(result):
    data=[]
    for box in result[0].boxes:
        data.append({"score":round(float(box.conf[0]),4),
                     "box":list(map(lambda x:round(float(x),2),box.xyxy[0].tolist()))})
    return sorted(data,key=lambda r:-r["score"])[:20]
details={
 "model":"yoloe-26n-seg.pt",
 "mode":"image example / visual prompt (SAVPE)",
 "reference":"ultralytics bus person crop at [221.52,405.8,344.98,857.54]",
 "positive_scene":"ultralytics zidane.jpg (contains people)",
 "negative_scene":"synthetic table with no people",
 "positive_boxes":summarize(positive),
 "negative_boxes":summarize(neg_result),
 "positive_highest":max([float(box.conf[0]) for box in positive[0].boxes],default=0.0),
 "negative_highest":max([float(box.conf[0]) for box in neg_result[0].boxes],default=0.0),
 "warmup_sec":warmup_seconds,
 "followup_cpu_sec":latency,
 "negative_cpu_sec":negative_latency,
 "notes":"This test establishes the image-guided pipeline, NOT user key-fob identity accuracy. Phone FPS untested.",
}
(REPORTS/"yoloe-visual-results.json").write_text(json.dumps(details,indent=2))
positive[0].save(filename=str(REPORTS/"positive-debug.jpg"))
neg_result[0].save(filename=str(REPORTS/"negative-debug.jpg"))
print("BENCHMARK_JSON",json.dumps({k:v for k,v in details.items() if k not in ("positive_boxes","negative_boxes")}),flush=True)
assert details["positive_highest"]>0.08, "Visual prompting not detecting cross-image positive example"
print("POSITIVE MODEL GATE PASSED",flush=True)
