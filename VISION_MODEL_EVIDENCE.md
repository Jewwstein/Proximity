# Plain Sight: actual image-guided object-finding research — 2026-10-21

## Why this experiment exists
The existing Android models repeatedly failed to distinguish the owner's black key fob from a mouse, controller, or microphone. Compilation is not an accuracy test.

## Models researched
- [Ultralytics YOLOE-26](https://docs.ultralytics.com/models/yoloe): pretrained image example / visual prompts with object boxes; nano model runs quickly after the prompt is encoded. Visual prompts can be baked into exported ONNX weights, but **the export is static**, so a new item requires generating new prompt-conditioned weights or implementing a runtime prompt encoder.
- [Google OWLv2](https://huggingface.co/docs/transformers/model_doc/owlv2): accepts query image and target image for image-guided detection. Works with dynamic exemplars, but is substantially more computationally expensive.
- [Meta SAM 3 / SAM 3.1](https://ai.meta.com/research/sam3/): text and image-exemplar prompt segmentation and video tracking. Larger model/server-class deployment; not validated as a fast on-device Android solution.

## Test evidence (GitHub Actions)
Tests use [this public-domain Nissan key photograph](https://commons.wikimedia.org/wiki/File:Intelligent_key.JPG), pasted into an image with multiple dark mouse/controller/microphone-like distractors. A negative scene contains only the distractors. **The positive is a composite**; real camera viewpoint, blur and lighting remain untested.

| Model | Key fob localization | False positive on key-free scene | CPU latency (GitHub worker) | Gate |
| --- | --- | --- | --- | --- |
| YOLOE-26n image visual prompt | Best IoU about **0.090** | Scores up to **0.058** on distractors | ~**0.09 s** after first prompt | **FAIL** |
| OWLv2 base patch16 ensemble | Found key precisely (best IoU **0.967**) | Also produced a spurious **1.0** score without any key | ~**28 s** per inference | **FAIL** |

The OWLv2 confidence is not a calibrated identity probability. That is why a high positive score alone does not meet the requirement to reject mouse/controller distractors.

An easy first positive/negative YOLOE person demo passed (1 detection in positive, zero on negative), but *that is not representative* of a tiny key fob and does not establish exact instance identity.

Raw benchmark sources: [YOLOE key-fob test](benchmarks/yoloe_real_fob.py), [OWLv2 head-to-head](benchmarks/owlv2_image_guided.py), [YOLOE visual example sanity check](benchmarks/yoloe_visual_prompt.py), and [Actions workflow](.github/workflows/visual-prompt-benchmark.yml). GitHub Actions artifacts contain debug images and JSON scores.

## Decision
Do **not** call another Android APK fixed solely by substituting either tested model. Both fail the required key-fob-vs-distractors gate; OWLv2 is also nowhere near instant on CPU.

A custom detector can be written, but weights trained only on positive pictures cannot reliably distinguish a specific fob from similar distractors. A viable custom learned-item system needs **hard negative samples**, accurately segmented object-only positives, higher-resolution proposals and calibrated recognition thresholds, evaluated against held-out scenes. Building this model from scratch without labels would not outperform trained foundation models. A 3D reconstruction is optional and does not solve a fob that covers too few pixels.

The current installed v0.8.0 should be regarded as **experimental** for personal items. Keep the working text/digit OCR separate and unchanged.
