# RoboAR v0.1 — Native Android ARCore

Prototype of live world-anchored procedural car-to-humanoid-robot transformation.

**Real limitations:** the phone does not yet recognize the actual car, reconstruct its body panels, or remove its image from the camera feed. It places a virtual transforming car near it.

Controls: scan a flat surface; tap where you want the virtual car; tap Transform; move phone to inspect world anchor; Paint, Size and Reset are available.

Build is automated by the repo's GitHub Actions workflow, which uploads the debug APK artifact and attempts to publish a pre-release downloadable APK. No Unity license is needed.
