# LOBBYISM

Location-based AR platform for Android. Content is left at real places and found through the camera.
Open, self-hosted, no dependency on platform companies. Phase 0a.

Owner: Yannick. Communicate in German, directly, no filler. Explain technical steps for a non-developer.

## Commands

```bash
# Android: unit tests, lint, debug APK (needs JDK 17 + Android SDK 35 + Gradle 8.10)
cd android && gradle testDebugUnitTest lintDebug assembleDebug

# Server: type-check, build, RPC tests (Node 22)
cd server && npm ci && npm run build && npm test
```

No Android SDK locally? Push to `main`: GitHub Actions runs everything and publishes the APK as a pre-release `build-N`.

## Layout

```
android/app/src/main/java/com/lobbyism/app/
  core/      Pure logic, no Android imports, unit-tested. Geo, trackers, lock, tutorial, home base.
  data/      Repositories (single source of truth), SQLite store, config loader, settings.
  net/       Nakama REST client and sync. Only NodeRepository uses it.
  sensors/   Gyro orientation, GPS.
  camera/    Camera2 feed into an OpenGL texture.
  render/    The micro engine: GL renderer, shaders, point-cloud geometry.
  feedback/  SensoryFeedback dispatcher, haptics, synthesised sound.
  ui/        MainActivity (composition root), GameLoop, state holders per feature, views.
    theme/   Colours, type, panels, buttons. Every look change starts here.
    home/    Home base state holder.
    tutorial/ Tutorial state holder and card.
android/app/src/main/assets/config.json   Every tunable value.
server/      Nakama module (TypeScript) + docker-compose with PostGIS.
docs/        Decision logs and ARCHITECTURE.md (feature map, data flow).
```

Feature → files map: @docs/ARCHITECTURE.md

## Architecture rules

- Layers follow the Android architecture guide: `ui` → `data` → (`net`, storage). `core` is used by all and depends on nothing.
- Unidirectional data flow: state holders own state, views render it, user events go back to the state holder as method calls.
- New logic that can be pure goes into `core/` and gets a unit test in `CoreTest.kt` first.
- Repositories are the only way to read or write data. UI code never touches `NodeStore` or `NakamaClient`.
- `MainActivity` only builds and wires parts. No feature logic there. Keep it under ~300 lines.
- Threads: `GameLoop.frame` runs on the GL thread every frame. It may only read `@Volatile` snapshots and must not allocate per frame. Views are touched on the main thread only (post via `Handler`).
- No library dependencies in the app (only Android framework + Kotlin stdlib). Adding one needs a decision log entry.

## Project rules (non-negotiable, from the design docs)

- R1: Bad GPS never costs gameplay. It may change visuals (glitch), never block or punish.
- R2: No magnetometer/compass. Orientation = `GAME_ROTATION_VECTOR`; north comes from GPS course.
- R4: Server authority. Client sends raw data; server validates everything.
- Never hardcode a tunable: add it to `core/Config.kt`, `ConfigLoader.kt` and `assets/config.json` together.
- Nothing may be told apart by colour alone (art constraint 12). Pair colour with shape, motion or a marker.
- Privacy: home base data never leaves the phone. No position in logs. Camera frames are never stored or sent.

## Workflow

- One feature per session. Read the matching section of `docs/ARCHITECTURE.md` first.
- Before pushing: Kotlin compiles, `testDebugUnitTest` green, server `npm test` green.
- Commit messages: imperative summary line, body says what and why.
- After a push, wait for the GitHub Actions run, download the APK and hand it to Yannick to test on the phone.
- Decisions that change architecture, rules or scope go into `docs/` as a decision log entry (format: see existing logs).

## Known limits (do not "fix" without a decision)

- Tracking is 3DoF + GPS. Home pins are correct only from their station's spot. 6DoF = stage 2 (OpenVINS), not started.
- Gyro yaw resets on every app start; north is relearned by walking, home stations by aligning.
- Licence not chosen yet (decision log NativeRuntime, O1). Do not add licence headers.
