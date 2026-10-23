# RiftLink native Android APK — v0.8.0
Independent Android client for the original RiftLink Windows host v0.7.2.
Android MediaCodec H.264 hardware video decoder, fullscreen TextureView and low-latency WebSocket input.
Connect to Windows PC on trusted home Wi-Fi at port 38775; enter the PC host IP and six-digit PIN.
First connection explicitly asks you to accept and pin the host's self-signed TLS certificate fingerprint. A changed certificate requires confirmation.
Native desktop trackpad, controller-layout editor and customizable keyboard-mapped game buttons (gamepad keys do not yet emulate XInput).
**Not yet implemented:** PC audio loopback, XInput/virtual Xbox controller driver, WAN relay / internet access.
The APK is an *unsigned release / debug-signed development build*. The Actions artifact ZIP contains app-debug.apk.
The existing Windows RiftLink host must be running; this is a client, not a host installer.
