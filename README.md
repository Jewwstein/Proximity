# Plain Sight v0.8.0 — Personal-object re-identification

The previous release overcorrected false matches and rejected a real key fob. This version adds **DINOv2-small** image embeddings to rank general YOLOE object detections against multi-angle photos of your own belongings.

- The live camera and object detector still operate offline.
- Search for an item with saved reference photos. The phone compares DINOv2 appearance embeddings for candidate regions to up to nine selected saved angles. It also checks geometric evidence.
- Strong multi-view geometric matches zoom and track automatically.
- When there is insufficient proof of identity, the app shows **POSSIBLE — YES, THAT'S IT / NEXT / NONE**, with an unchanged capture frame behind the proposed box. **No more treating guesses as verified finds or silently hiding all uncertain results**.
- Confirming a real object adds a photograph of that object in its real environment to the item memory, improving the reference set. Wrong guesses are not auto-added.
- Live text/digit OCR, universal object detection, zoom/tracking and the option to scan 24 views are retained.

The DINOv2 model is a 24.5 MB quantized ONNX neural network downloaded and validated in a separate GitHub Action before being bundled in the Android APK. Real-phone accuracy and latency remain unverified. It may still fail on tiny, occluded or reflective objects, and DINOv2 does not prove that two images contain the exact same physical item.

Security/privacy: images and embeddings processed on-device; no Gemini API key or cloud image recognition calls. Learned photos are stored in local app preferences. License obligations apply to YOLOE under AGPL-3.0; review before distributing commercially.
