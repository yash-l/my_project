# OpenLens Native VR — license-free experimental APK

This is a NEW native Android/Gradle/OpenGL ES 2.0 project. It is not a compiled version of the Unity OpenLensVR source and does not use CardboardXR code or the Unity runtime.

## What exists
- Side-by-side stereo eye renders at roughly 60 fps with a virtual floor grid and three spheres.
- Adjustable-in-source 63 mm software IPD, simple radial lens distortion, black UV borders, and experimental application-level rotational timewarp using the latest gyroscope quaternion before compositor.
- Optional ARCore 6DoF pose and rear-camera access; gyroscope-only 3DoF fallback when ARCore is unavailable or thermal load is severe.
- MediaPipe Hand Landmarker on ARCore camera CPU frames (one hand, 21 landmarks), approximate 2D stereo overlay and a pinch threshold with hysteresis.
- Volume key as gaze trigger; volume long press toggles timewarp. No real interaction physics or full VR world-space menu yet.
- PowerManager thermal level reduces eye texture resolution and suspends ARCore frame processing when severe.

## LIMITATIONS
- Not tested on a real OnePlus Nord CE5. Build passing is not evidence of visual comfort, stereo calibration, 6DoF correctness or reliable hand tracking.
- Stereo refraction model, orientation/camera coordinate mapping and timewarp matrix need headset A/B testing. True asynchronous timewarp is not provided.
- Camera-to-eye displacement, monocular hand depth, AR camera orientation, persistence, calibration UI, 3D hand joints, grab interactions and positional prediction are not yet production-grade.
- ARCore missing/deactivated: no hand camera frames, so no hand tracking in fallback.
- Hand inference is CPU-heavy; high temperatures cause temporary 3DoF protection.
- This is a proof-of-concept intended to unblock APK delivery without Unity licensing, not a drop-in Unity application replacement.

## Build
Requires JDK 17, Android SDK 35, Gradle 8.10.2, network for Google Maven and MediaPipe model.
GitHub workflow .github/workflows/build-openlens-native-apk.yml produces app-debug.apk under its Actions Artifacts.

## Legal
Original project source code authored for this project, offered under Apache-2.0. Third-party Google ARCore and MediaPipe binaries/models retain their respective licenses.
