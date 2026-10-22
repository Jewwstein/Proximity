# EchoSight — experimental acoustic + low-light visual sensor

A standalone, offline Android test app combining a live camera feed with acoustic echo measurements from the phone speaker/microphone. Does **not** depend on the Plain Sight codebase. [GitHub branch](https://github.com/Jewwstein/Proximity/tree/echo-sight-android).

## What is real, and what isn't

- **Enhanced camera light**: actual back-camera pixels are amplified and colored. Small camera sensor noise may also be amplified. If there is zero light, the app cannot draw real camera contours.
- **Glowing cyan contours**: detected from denoised camera pixels. They are NOT generated from sonar.
- **Sonar**: tapping START SONAR emits soft ~15.4–17.7 kHz chirps using the phone speaker. The microphone records them at 48kHz; a matched filter looks for a direct arrival and delayed reflection peaks. When strong enough, it displays an **experimental, approximate reflection range band**. This signal has **no measured direction** and **cannot generate a wall/door or floor map** from one microphone.
- **Rings and range bars**: a stylized, *non-directional* visualization of the measured echo strength. The location of a ring on the display is NOT a detected object location.
- **Modes**: FUSION (camera plus edges), OUTLINES (only camera edges), ENHANCED (brighter camera). Glow slider adjusts enhancement. LAMP is an optional physical flashlight, OFF by default. Microphone permission is only requested when SONAR is first enabled.
- **No cloud or API key**, no network permission, no recordings saved or uploaded.

## How to test
1. Install EchoSight APK, allow the camera.
2. Start in a *dimly lit* room. Try FUSION and OUTLINES; move around. The displayed cyan outlines should correspond to real visible camera edges.
3. Turn the light fully off: camera-derived contours will disappear if sensor receives no usable light. This is expected—not a failure of sonar.
4. Tap START SONAR and allow microphone. Hold still and point the phone toward a nearby large flat wall, then change distance; watch the echo strength/range-only panel. The first real-device test is primarily to determine whether the built-in speaker and mic capture meaningful high-frequency chirps at all.
5. If you hear the high frequencies or have sound-sensitive people or pets nearby, stop SONAR.

## Important limitations and safety
**NOT FOR WALKING OR OBSTACLE AVOIDANCE.** The current audio measurement is a single-channel, hardware-dependent echo experiment. Some phones filter high-frequency chirps or apply signal processing that makes measurements impossible. Echo ranges can be wrong; device latency, multipath and room acoustics can generate false positives. Do not assume floors, doors, windows, stairs, dropoffs, or obstacles are mapped. If needed, turn on LAMP for a real light source.

Build validation only confirms compilation and packaging, **not actual hardware sonar functionality**. There is no real 3D reconstruction, AR depth tracking, stereoscopic audio localization, LiDAR, or perfect night vision.

## Build
Gradle 8.10.2 + Android Gradle Plugin 8.8.2 + Android SDK 35 + Java 17. `gradle assembleDebug`. GitHub Actions builds a debug-signed installable APK and publishes a prerelease at tag `echo-sight-v0.1.0`.
