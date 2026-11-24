---
paths:
  - "android/app/src/main/java/com/lobbyism/app/render/**"
---

# Rendering rules

- Shaders are GLSL ES 1.00 (OpenGL ES 2.0) so they run on the Galaxy A50. No ES 3 features.
- Validate every shader change before pushing: extract the sources from `Shaders.kt` and run `glslangValidator` on each (`.vert` / `.frag`).
- No allocation inside `onDrawFrame` or anything it calls: reuse the scratch arrays in `ArRenderer`.
- Effects are event-driven (a `SensoryFeedback.Cue` sets a pulse that decays), never a permanent heavy effect (Art Constraints 13).
- The real camera image is never tinted, grained or distorted. Effects apply to LOBBYISM elements only.
- Home pins vs public nodes must differ by colour AND motion (pulse amount, spin speed).
