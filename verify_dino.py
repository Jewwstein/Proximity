import os, numpy as np, onnxruntime as ort
from PIL import Image,ImageDraw
path="app/src/main/assets/dinov2-small.onnx"
sess=ort.InferenceSession(path,providers=['CPUExecutionProvider'])
print("DINO_INPUTS",[(x.name,x.shape,x.type) for x in sess.get_inputs()],flush=True)
print("DINO_OUTPUTS",[(x.name,x.shape,x.type) for x in sess.get_outputs()],flush=True)
mean=np.array([.485,.456,.406],dtype='float32').reshape(3,1,1)
std=np.array([.229,.224,.225],dtype='float32').reshape(3,1,1)
def encode(im):
 a=np.asarray(im.resize((224,224)).convert('RGB'),dtype='float32').transpose(2,0,1)/255
 t=np.ascontiguousarray(((a-mean)/std)[None],dtype='float32')
 out=sess.run(None,{sess.get_inputs()[0].name:t})
 for i,o in enumerate(out):
  print("OUTPUT",i,o.shape,float(np.asarray(o).mean()),flush=True)
 # The pooled CLS embedding is generally the second output; otherwise use token 0.
 v=out[1][0] if len(out)>1 and len(out[1].shape)==2 else out[0][0,0]
 v=np.asarray(v,dtype=np.float64)
 return v/(np.linalg.norm(v)+1e-9)
a=Image.new('RGB',(224,224),'white');b=Image.new('RGB',(224,224),'white');c=Image.new('RGB',(224,224),'white')
for im,shape in ((a,0),(b,1),(c,2)):
 d=ImageDraw.Draw(im)
 if shape==0:d.rounded_rectangle((70,30,155,190),radius=16,fill='#171a1d');d.ellipse((96,55,129,88),fill='silver')
 if shape==1:d.rounded_rectangle((72,33,158,188),radius=22,fill='#18191d');d.ellipse((101,57,132,87),fill='silver')
 if shape==2:d.ellipse((40,60,185,160),fill='#171a1d')
u,v,w=encode(a),encode(b),encode(c)
print("DINO_SMOKE similar",round(float(u@v),4),"different",round(float(u@w),4),flush=True)
print("DINO_MODEL_BYTES",os.path.getsize(path),flush=True)
