import json,os,time,urllib.request
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageOps
model="out/plainsight-yoloe.onnx"
with open("out/categories.json") as f: names=json.load(f)
session=ort.InferenceSession(model,providers=["CPUExecutionProvider"])
print("Model",names[:12],flush=True)
print("Inputs",[(i.name,i.shape) for i in session.get_inputs()])
print("Outputs",[(i.name,i.shape) for i in session.get_outputs()])
url="https://images.pexels.com/photos/3162037/pexels-photo-3162037.jpeg?auto=compress&cs=tinysrgb&w=720"
try:
 urllib.request.urlretrieve(url,"sample.jpg")
 print("REAL PHOTO DOWNLOADED",flush=True)
except Exception as e:
 print("REAL IMAGE UNAVAILABLE, using synthetic scene for tensor/latency test only:",e,flush=True)
 from PIL import ImageDraw
 test=Image.new("RGB",(640,640),(155,156,159))
 draw=ImageDraw.Draw(test)
 draw.ellipse([160,160,480,530],outline=(220,220,225),width=9)
 draw.line([335,360,435,60],fill=(238,50,48),width=18)
 test.save("sample.jpg")
img=Image.open("sample.jpg").convert("RGB").resize((320,320))
arr=np.array(img,dtype=np.float32)/255.
batch=np.ascontiguousarray(arr.transpose(2,0,1)[None])
start=time.time()
pred=session.run(None,{session.get_inputs()[0].name:batch})[0]
print("Single-run CPU seconds",round(time.time()-start,3),"Output shape",pred.shape)
# Raw class scores: first 4 are coordinates, one row per class.
results=[]
if pred.shape[1]>=4+len(names):
 for i,n in enumerate(names):
  scores=pred[0,4+i,:]
  j=int(scores.argmax())
  results.append((round(float(scores[j]),3),n,int(j),pred[0,:4,j].tolist()))
else:
 print("Unsupported layout for this smoke test:",pred.shape)
results.sort(reverse=True)
print("TOP SCORES:",results[:20])
print("STRAW SCORES:",[x for x in results if "straw" in x[1]])
assert pred.size>100, "Empty model output"
