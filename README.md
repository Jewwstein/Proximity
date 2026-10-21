# Plain Sight v0.5.0 · fast offline detector

This build **does not call Gemini** for searches. It uses an on-device YOLOE-26n detector exported with 62 categories including keys, car key fobs, drinking straws, cups, glasses, tools, clothing, remotes and more. Android's bundled ML Kit OCR recognizes numbers and written words such as 'find the number seven', independent of the object detector. Both modes return bounding rectangles to the always-live native camera.

- No API key is required. Old saved test key is cleared from the app's preferences.
- Tap FIND or use voice. Numbers/letters use offline text recognition; named supported objects use YOLOE.
- Detection runs on a background executor, avoiding a frozen preview.
- The detector is a **bounded** vocabulary, not truly universal. It cannot reliably locate every novel object, and small thin straws or shiny key fobs may not be detected in difficult scenes.
- The saved-image 'learn' flow is retained, but those eight photos are not yet used by this local detector. We will not claim that individual identity matching is working.
- The Ultralytics YOLOE model carries AGPL-3.0 licensing. Review its obligations before commercial redistribution.
- **No physical-device testing yet**. GitHub Actions compilation alone is not a correctness guarantee.

## Build
GitHub Actions fetches a successful model-export artifact created by `export-yoloe.yml`, bundles the ONNX model and category list in the APK, and builds a debug-signed APK with Android CameraX, ONNX Runtime, and ML Kit OCR.
