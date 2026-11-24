---
paths:
  - "android/app/src/main/cpp/**"
  - "android/app/src/main/java/com/lobbyism/app/camera/Marker*"
---

# Native code rules (marker detector)

- `cpp/apriltag/` is vendored AprilTag v3.4.5 (BSD-2-Clause). Never edit it; update by replacing the folder and noting the version in its README.md.
- Our own C lives only in `cpp/lobbyism_marker.c` (the JNI bridge). Keep it thin: detect, estimate pose, copy numbers out.
- The JNI output layout (14 doubles per marker) is documented in `MarkerNative.kt`; change both sides together.
- Frames are analysed in place (direct ByteBuffer of the Y plane) and released at once. Never copy, store or send camera frames.
- Test detector changes locally before pushing: build the vendored sources with gcc and run a synthetic-pose test (render a tag with a known pose, detect, compare R and t).
- Camera geometry (axes, intrinsics, frame transforms) belongs in `core/CameraGeometry.kt` with unit tests, not in camera/.
