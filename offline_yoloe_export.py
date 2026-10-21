from ultralytics import YOLOE
import os,json
classes = [
 "key", "keys", "car key", "car key fob", "keychain", "key ring",
 "straw", "drinking straw", "plastic straw", "metal straw",
 "cup", "coffee cup", "mug", "bottle", "glass", "spoon",
 "fork", "knife", "scissors", "remote control", "remote",
 "mobile phone", "wallet", "eyeglasses", "sunglasses",
 "clock", "digital clock", "watch", "digital watch",
 "pen", "pencil", "headphones", "charger", "charging cable",
 "t-shirt", "shirt", "blue shirt", "black shirt", "red shirt",
 "shoe", "hat", "book", "computer mouse", "toothbrush",
 "toothpaste", "ketchup bottle", "food container", "backpack",
 "flashlight", "battery", "napkin", "hair tie", "rubber band",
 "toy", "car", "steering wheel", "cup holder", "seat belt",
 "screwdriver", "tape measure", "thermos", "lighter"
]
os.makedirs("out",exist_ok=True)
print(f"Exporting {len(classes)} baked-in object prompts",flush=True)
model=YOLOE("yoloe-26n-seg.pt")
model.set_classes(classes)
result=model.export(format="onnx",imgsz=320,opset=17,simplify=False,device="cpu")
print("Exported:",result,flush=True)
import shutil
shutil.copyfile(result,"out/plainsight-yoloe.onnx")
with open("out/categories.json","w",encoding="utf-8") as f: json.dump(classes,f,ensure_ascii=False)
import onnx
m=onnx.load("out/plainsight-yoloe.onnx")
print("Input:", [(i.name,[str(d.dim_value) if d.dim_value else d.dim_param for d in i.type.tensor_type.shape.dim]) for i in m.graph.input],flush=True)
print("Output:",[(o.name,[str(d.dim_value) if d.dim_value else d.dim_param for d in o.type.tensor_type.shape.dim]) for o in m.graph.output],flush=True)
print("Size bytes",os.path.getsize("out/plainsight-yoloe.onnx"),flush=True)
