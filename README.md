# EchoSight 0.3.0 — Spatial Memory (Android)

This release is a **separate sensing experiment** from the v0.1-v0.2 acoustic sonar prototype. The goal is to scan real geometry in light and retain it as a ghostly map after the lights go off, using the Motorola Razr's actual built-in cameras and motion sensors.

## Modes

### Scan in light
Uses **Google ARCore 1.56.0** if it runs on your device. Google Play Services for AR may need installation/update. When camera tracking is available, the app continuously captures real-world ARCore 3D feature points and, when the device supports the Depth API, sparse 3D samples reconstructed from depth images. This results in a **sparse dot cloud, not a polished, complete, navigable 3D floorplan**. Scan a lit room slowly from different angles. ARCore's mapping coordinate frame may drift across several rooms.

### Save map
Persists up to 68,000 deduplicated 3D points and the last lit-view camera position/orientation in a private local binary file. No room images are saved or uploaded.

### Dark memory
Renders saved points as glowing cyan 3D dots against pure black, using the **last recorded camera position**, and follows phone **rotation** with its rotation-vector sensor. Swiping the view explores virtually. It requires no visible camera image, flash, or audio.

**This is NOT the same as tracking your physical location while you walk in pitch darkness.** It does not estimate translation after the camera can no longer see features; the viewpoint position is deliberately frozen. If you reopen the map after moving somewhere else, the stored viewpoint does not relocate to your actual position. Current orientation relative to last mapped view is established at the moment you select dark memory; it's not a full persistent relocalization.

### Safety
**NOT FOR WALKING, NAVIGATION OR OBSTACLE AVOIDANCE.** The dark display cannot detect furniture moved since the scan, stairs, drop-offs, people, doors, or hazards. Even the saved geometry is incomplete and may be wrong; rotations can drift. Do not walk in darkness using this display.

## Hardware support
- The ARCore runtime determines whether a given phone supports tracking and optional Depth API. **The Motorola Razr 2025 was not explicitly identifiable on Google's published supported model list, so actual ARCore availability is not assumed.**
- A missing Depth API still permits sparser ARCore point-cloud mapping if ARCore tracking works.
- Without ARCore support, the app clearly reports the limitation, and can display a previously saved local map if one exists.
- This mode does **not** require a new sensor attachment. It may ask to install Google Play Services for AR if missing.

## Install and test
1. Install EchoSight v0.3.0 Android APK over the old EchoSight. No Plain Sight changes.
2. Tap **SCAN IN LIGHT**, grant camera access, and accept the Google Play Services for AR installation if prompted.
3. Point toward textured walls, furniture, doorway edges and the floor, moving **slowly**. The screen should show a genuine camera view and a count of mapped points.
4. Press **SAVE MAP** after the counter rises, then **DARK MEMORY**. Turn your phone while standing **in the same place**, to see the saved dot cloud move relative to its remembered viewpoint.
5. Do not physically walk in the dark based on the map. Return to **SCAN IN LIGHT** to realign and extend the map when lighting is available.

The debug APK is compiled in GitHub Actions. A successful build does **not** establish device ARCore compatibility, depth accuracy, map registration, or navigation safety.
