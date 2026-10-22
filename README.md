# EchoSight v0.2.0 — repaired Android speaker and camera experiment

Standalone Android app on [branch echo-sight-android](https://github.com/Jewwstein/Proximity/tree/echo-sight-android). This version replaces the v0.1 sonar audio implementation that reported **speaker unavailable** on some devices.

## What's repaired
- The original audio driver used a `MODE_STATIC` AudioTrack containing only 10 milliseconds of audio. This is below the minimum hardware buffer size on some Android devices. EchoSight now uses `MODE_STREAM` with `AudioTrack.getMinBufferSize()` and suitable buffering.
- Automatically attempts **48 kHz / 44.1 kHz**, mono / stereo speaker output, and microphone sources UNPROCESSED, VOICE_RECOGNITION, and MIC. Distinguishes microphone initialization failures, speaker initialization failures, and failure to detect a direct chirp.
- Emits separated high-frequency chirps and continuously captures microphone audio using different recording and playback threads. The echogram looks for matched signal timing (tentative, not calibrated).
- New **PING: HIGH / PING: AUDIBLE** toggle. High uses approximately 14.5–16.9 kHz; audible fallback is 9.1–12 kHz for phones that filter high frequencies. Audible mode can be unpleasant to sensitive people and animals; use only briefly and stop if uncomfortable.
- Camera enhancement has an optimized gamma lookup table, is optional, and reveals the underlying raw camera preview when processed frames aren't available.
- Camera fusion modes (FUSION, OUTLINES, ENHANCED), glow slider, optional flashlight, experimental echo rings and strength bars remain separate from acoustic direction data.

## How to try
1. Install EchoSight v0.2.0 (debug-signed replacement for v0.1.0), allow camera.
2. In a dim—not totally dark—room, try FUSION and OUTLINES; camera contours originate only from actual image pixels.
3. Tap START SONAR, allow microphone permission. Watch the SONAR status. If it says the audio is ready but no chirp arrives, stop and select PING: AUDIBLE, then start again.
4. To compare measurements, try holding the phone still facing a large wall and change distance. Phone audio signal processing and reflections can generate false positives; do not treat any reading as exact.

## Hard limitations
**Not safe for walking, navigation, or obstacle avoidance.** The single microphone gives no direction to the reflecting object; the rings are stylized, non-directional range indicators. It cannot form a 3D wall/floor map in total darkness. Visible camera contours require some real light, and acoustic reflections are hardware-dependent and uncalibrated.

APK built through GitHub Actions, **not field-tested** on a Motorola Razr. Android hardware compatibility must be confirmed on a real device. No network, cloud processing, or saved audio recordings.
