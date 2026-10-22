# EchoSight v0.4.0 — Wider Scan, Measured Surfaces & Experimental Steps

A standalone Android ARCore spatial-memory experiment designed for a tall-screen Motorola Razr 2025. The prior v0.3.1 scanned an overly cropped preview and showed only disconnected sparse points.

## What's new

- **Wide camera view**: ARCore frames default to a smaller **3:4 portrait display rectangle**, letterboxed instead of aggressively cropping the camera to the entire tall screen. Tap **WIDE CAM / FULL CAM** to compare. This changes display cropping, **not** the physical camera lens or its true field of view. Hardware zoom cannot be overcome by software.
- **Real geometric surfaces**: collects substantially more ARCore point-cloud features and, if the phone supports ARCore Depth API, extracts local connected lines from actual depth samples (only neighboring measurements are connected). Also traces boundaries of genuine ARCore-detected horizontal and vertical planes, which can help when the Depth API is unavailable. No arbitrary room walls are invented.
- **Depth view controls**: DARK MEMORY now defaults to glowing depth/plane **SURFACES**; switch to **POINTS** or **BOTH**. A top-down inset uses the same measured point geometry and indicates the estimated viewpoint.
- **Saved map**: v0.4 persists up to 145k feature/depth points plus up to 80k measured edge segments. Old v0.3 maps load, but are only point clouds and **need a new lit scan to gain surfaces**.
- **EXPERIMENTAL STEP estimation**: Android `TYPE_STEP_DETECTOR` or `TYPE_STEP_COUNTER` drives approximate step events. The phone rotation sensor gives an estimated forward direction, with a calibrated stride (STEP .50 / .63 / .76 / .90 m). Explicitly opt in with TRY STEPS; Android asks for ACTIVITY_RECOGNITION permission. The app assumes you held the phone pointing **in the actual direction you walked**, and starts at the last known scanned camera position; it does NOT truly know where you are. Position uncertainty grows and the demo stops after 24 steps. A top-down uncertainty circle and orange warning remain.
- **No extra attachments**, no internet permissions, no saved camera photos or audio. Maps are kept privately on-device.

## Use

1. Install v0.4 over v0.3. Scan while the room is lit, with textured walls/furniture, moving smoothly sideways and around surfaces. The preview should have letterboxes rather than extreme crop.
2. If supported, Depth API samples and ARCore wall/floor plane polygons will accumulate alongside regular 3D points. The viewer draws their evidenced connections. It does not guarantee all room surfaces are reconstructed.
3. Save your scan, then tap DARK MEMORY, while still at the mapped endpoint. Turn the phone to explore the saved geometry and inspect the overhead inset.
4. The **TRY STEPS** switch provides only a motion-estimation experiment. It is **not safe to use for walking in actual darkness**. Test it in a well-lit, cleared, stationary/controlled environment, with the phone facing forward, and compare the change in the overhead position marker.
5. Recalibrate the stride length to your typical pace. Drift is uncorrected without a new visual relocalization. Reopen DARK MEMORY to reset the estimated position to the saved anchor.

## Hard limitations
- Neither a camera without light nor gyroscope+pedometer can tell which hallway you are standing in. The phone does not measure true translational displacement while in darkness.
- Turning the phone to look sideways while walking can confuse **heading versus walking direction**.
- Step counts, stride length, gyroscope heading, and starting orientation cause accumulated drift. A saved map may not correspond to the real room after furniture moves.
- **NOT A NAVIGATION SYSTEM. DO NOT USE IT TO AVOID OBSTACLES, STAIRS OR DROPOFFS IN THE DARK.**
- This is tested for Android compilation, **not** validated on the actual Motorola Razr, ARCore tracking quality, or sensor behavior.
