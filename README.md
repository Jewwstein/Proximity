# EchoSight v0.5.0 — Actual Photographic Room Memory

This is a **different strategy** from the v0.4 dot-cloud/wireframe viewer.

## What it does

1. **SCAN IN LIGHT:** EchoSight records real color camera photographs while you sweep the phone slowly through your room, moving between viewing positions and angles. Alongside each image it stores the ARCore camera **world position and orientation** from that scan session. Photographs come from the live ARCore OpenGL camera viewport, so they're already correctly oriented as displayed. It captures at most one image every ~0.85 seconds, only when the viewpoint changed sufficiently (roughly 26 cm or 17 degrees). Up to 240 photos are held per scan, reduced to max 800px before being stored on the phone. It may miss views if you move too quickly or tracking drops.
2. **SAVE MAP:** Local photo files, frame positions, and mapped geometry are stored on the phone. No network permission and no image upload.
3. **DARK MEMORY:** Rather than glowing wireframe points, the app shows a recorded photo of the **actual room** from the nearest available estimated viewpoint and orientation. You can turn your phone or swipe to switch between recorded views.
4. **TRY STEPS:** Android's step sensor advances an **assumed** walking position from the last recorded scan endpoint; phone heading steers the estimate, and the app chooses a saved photo whose position and direction best match. Displays uncertainty and stops step estimates after 24 events. The map includes a small overhead sketch when spatial points are available.

## What this is NOT

This is a **spatially indexed photographic tour**, not a continuous textured 3D reconstruction. The viewer shows only images actually captured while mapping. You may see discrete jumps, a missing photo, or a picture of the wrong spot if the step length or initial direction is inaccurate. It cannot see newly introduced obstacles, people or open doors. It has no camera localization in pitch darkness. If no recorded image matches the approximate pose, the photo disappears rather than pretend to show an object that's not there.

Real photogrammetry, NeRFs, Gaussian splatting and mesh-texturing could interpolate viewpoints, but producing a high-quality room model would need substantial image overlap, offline reconstruction and reliable tracking; those steps are **not part of v0.5**.

**DO NOT WALK AROUND IN DARKNESS WITH THIS.** It is an experimental replay system and cannot be used to avoid obstacles, stairs, pets, or tripping hazards.

## Instructions

- Install APK (same app ID as previous EchoSight).
- In a room with lights ON, press **SCAN IN LIGHT**. Walk slowly to several locations and scan each area from different angles. Include your doorway, bed, desk, and corners, and capture overlapping views. The scan status should count `ROOM PHOTOS`—unlike before, these are images, not arbitrary point dots.
- Tap **SAVE MAP**, then **DARK MEMORY**. Look around while **standing still** and you should see actual recorded photos. Swipe the screen to rotate the virtual tour.
- For TRY STEPS, keep the lights on and floor clear while evaluating the estimate; it does not know your location. Photos match approximate pose, not physical motion measured by a camera in darkness.

Build status: validated by Android Gradle GitHub Actions. No physical Razr performance, ARCore camera orientation, or photo alignment validation has been performed.
