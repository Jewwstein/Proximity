# Plain Sight v0.4.1 — Real AI vision

This iteration **replaces** the old weak ML Kit whole-image classifier and MobileNet visual similarity matcher. They did not do arbitrary object detection, which is why the first versions failed the key-fob test.

## Current test APK
- CameraX preview always live (camera isn't deliberately paused).
- Type or speak **Find my keys**, **Find the ketchup**, or **Find the blue shirt**; a Gemini 3.8 Flash API request tries to return actual bounding box coordinates around the requested item in the live camera frame.
- Use + to learn your key fob from 8 rotated views; it stores 8 compressed JPEG photos privately on device. Search that saved name to send 4 reference views **and** a new live scene to Gemini.
- A blue outline and a magnified inset appear when Gemini returns a plausible match.
- No large ML models to download. App requires network during searches.
- API authentication **required**: enter your own Google AI Studio Gemini API key inside app using AI KEY button. For testing only. **Never place shared/proprietary API keys in client applications**. Commercial release requires backend-based auth, quota controls and key protection.
- Camera data and optional saved reference photos are transmitted to the Gemini API on FIND; photos stay stored locally until a request.
- The app does NOT create a true 3D model or optical camera zoom.

## First run
1. Install from the v0.4.1 prerelease; allow camera.
2. Tap **GET A GOOGLE AI STUDIO KEY** in the initial key screen, generate your own key, and paste it in the app. Don't post your key in chat, GitHub, or screenshots.
3. To test universal mode, aim at your keys and speak "Find my keys" then keep the phone still for the request. On a return the app highlights a box or honestly says it couldn't identify the object.
4. For personal mode, tap +, give your item the same name you'll search, and rotate for eight captured photos. Then FIND sends selected reference views alongside the camera image.
5. Every press of FIND sends a request that may use rate limits or billing quota. Do not run repeated searches unnecessarily.

## Not yet validated
GitHub Actions confirms Android **compilation** only. We have not demonstrated that the external API will return the correct key-fob bounding box on the user's phone. Cloud model calls may be delayed, charge for usage, or fail on account permissions/rate limits. Accuracy remains experimental.

Official image localization API: https://ai.google.dev/gemini-api/docs/image-understanding
API key safety: https://ai.google.dev/gemini-api/docs/api-key
