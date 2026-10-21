# Plain Sight Android v0.7.1

Native Android application, source in the `plain-sight-android` branch.

## Improvements over v0.6.1

- Retains the proven fast on-device ML Kit recognition of printed text and digits.
- General YOLOE object detector scans the **whole camera frame plus overlapping tiles**, instead of needing the target centered in a narrow view.
- When searching a trained item, detection and saved multi-view image matching are **combined**, so a failed exact reference match does not suppress universal recognition. A unique saved name like 'house keys' also matches a query for 'keys'.
- The training action captures **24 distinct timed views** as you rotate and tilt an object. Previous 8-view saves remain compatible.
- On detection the camera and highlight zoom **together** using Android GPU view transformations. Lower-resolution optical-flow frames update the selected position and the displayed live camera zoom. Tap the view to pause on the last matched frame, then tap the frozen view to search again.
- Tracking uses fewer optical-flow points and reuses features for multiple frames. The detector is not re-run for each camera frame.

## Limitations / test scope

The saved multi-angle photographs are **not a reconstructed 3D mesh**; that is not required for multi-view visual matching and does not solve occlusion of fully hidden items.
Recognition remains experimental for distant, tiny, low-contrast, reflective, or heavily occluded objects. The local YOLOE detector has 62 preselected classes, not literally universal recognition. Feature geometry can fail for shiny or textureless keys. Learned references and universal predictions are candidates, not guaranteed identities. The app has been built via GitHub Actions but **not tested on the user's phone**. No performance promise can be made without testing on a physical device.

No cloud AI calls or API key are used during searches. Camera images and learned reference photos stay local. YOLOE weights have Ultralytics AGPL-3.0 licensing; review before paid distribution.
