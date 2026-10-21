# Plain Sight — Android test build

Open the app. Point its camera at a scene. Speak or type **Find my keys**. It analyzes the current camera frame using on-device open-vocabulary image detection and highlights the strongest visible match, then digitally zooms into the captured region.

## Teach personal objects (v0.2 experimental)

Tap **+** on the camera screen, name an item, and hold it centered while slowly rotating for eight automatic photos. The app saves compact on-device image-feature vectors (not a real geometric 3D model). Ask for the saved name to search the scene by sliding-window image similarity. Normal unsaved-name searches still use universal text-guided detection.

Visual comparisons are approximate, not identity proof. Small reflective keys and partially hidden objects can be difficult. The first personal search downloads a small MediaPipe vision model. Personal data is kept in local app storage, and users can delete saved items in the same panel.

## Test
1. Install the APK from the latest **Plain Sight** prerelease or workflow artifact.
2. Allow camera access and provide internet access on first launch.
3. Point at your car seat with a set of keys and other objects in full view.
4. Say **Find my keys** (Android speech recognizer) or type it and press **FIND**.
5. On first search, allow the ~160 MB quantized OWL-ViT model to download; later scans reuse the browser model cache when retained.
6. A box and digital zoom show the best candidate; **AGAIN** resumes searching another frame.

The current build is **experimental**, not a guaranteed finder for all objects. Small shiny keys, occluded objects and cluttered scenes can fail. A single camera frame cannot reconstruct a genuine 3D object, so this version uses digital zoom and a highlighted bounding box instead.

## Technology
- Native Android Java WebView shell with secure HTTPS asset origin, live camera permission, and Android speech recognition
- Transformers.js 3.8.1 / OWL-ViT open-vocabulary object detector, quantized WASM model downloaded once from Hugging Face; no subscription or backend API key
- Privacy: camera frames processed locally; the AI runtime and model files are downloaded over HTTPS at first use
- Android 8.0+, compile SDK 35, Android Gradle Plugin 8.8.2
- GitHub Actions builds unsigned-for-production **debug-signed installable APK** from the branch.

Source lives exclusively on `plain-sight-android` branch, separate from main; it does not alter the existing app code.

## Licensing
Google OWL-ViT model: Apache 2.0. Hugging Face Transformers.js: Apache 2.0. Review upstream notices before commercial redistribution. This proof of concept is not yet validated on a physical Android phone.
