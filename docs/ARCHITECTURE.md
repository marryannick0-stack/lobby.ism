# LOBBYISM — Architecture and feature map

How the app is organised and where each feature lives. Read the section for the feature you work on before changing it.

## Data flow

```
 Sensors (gyro, GPS)                     Repositories (data/)
        │                                 NodeRepository ── SyncEngine ── Nakama server
        ▼                                 HomeRepository   (local only)
 GameLoop.frame()  ◀── reads snapshots ───────┘
   │  every frame, GL thread
   ├─ WorldTracker (outdoor)  or  HomeController → HomeTracker (home base)
   │     └─ LockEngine (shared gyro lock)
   ├─ events ─▶ SensoryFeedback ─▶ Haptics · SignalAudio · ArRenderer (glitch pulse)
   │       └─▶ ContentCard (main thread)  └─▶ TutorialController
   ├─ HudView.State (status, reticle, turn hint)
   └─ NodeViews ─▶ ArRenderer draws camera → point clouds → signal-degradation pass

 User taps ─▶ ActionButtons ─▶ state holder method (HomeController, TutorialController, MainActivity.askForText)
            ─▶ repository write ─▶ new snapshot ─▶ next frame shows it
```

Rules behind this: see `CLAUDE.md` → Architecture rules.

## Feature map

| Feature | Logic (tested) | UI / glue | Look | Config keys |
|---|---|---|---|---|
| Node discovery: materialise, pinning | `core/WorldTracker.kt` | `ui/GameLoop.kt` | `render/NodeGeometry.node`, `render/Shaders.kt` POINTS | `materialize*`, `pin*`, `cameraHeightM`, `nodeHoverM`, `scanRadiusM` |
| Sky pillar (light beam) | `core/WorldTracker.kt` (Stage.PILLAR) | `ui/GameLoop.kt` | `render/NodeGeometry.pillar`, `ArRenderer` constants `PILLAR_*` | `pillarMaxDistanceM` |
| Gyro lock | `core/LockEngine.kt` | `ui/HudView.kt` reticle | `feedback/*` cues `LOCK_*` | `lockAngleDeg`, `lockTimeS`, `lockDecayS` |
| North without compass | `core/HeadingEstimator.kt` | `ui/GameLoop.onGpsFix` | HUD "NORD SYNC" | `heading*` |
| Leave a message | `data/NodeRepository.placeText` | `ui/MainActivity.askForText` | `ui/ContentCard.kt` | `textMaxChars`, `placeMaxAccuracyM` |
| Home base | `core/HomeBase.kt` | `ui/home/HomeController.kt`, `ui/ActionButtons.kt` | warm colour + calm pulse in `ArRenderer` | `homePinDistanceM`, `homeNodeSizeM` |
| Tutorial / calibration walk | `core/Tutorial.kt` | `ui/tutorial/*` | `ui/tutorial/TutorialOverlay.kt` | `tutorial.*` |
| Glitch look | — | — | `render/Shaders.kt` COMPOSITE, `ArRenderer.onCue` | `layerScale` |
| Haptics and sound | — | — | `feedback/Haptics.kt`, `feedback/SignalAudio.kt` | — |
| HUD and buttons | — | `ui/HudView.kt`, `ui/ActionButtons.kt` | `ui/theme/Theme.kt` | — |
| Permissions and privacy notice | — | `ui/PermissionGate.kt` | — | — |
| Server sync | — | `net/SyncEngine.kt`, `net/NakamaClient.kt` | — | `sync*`, `httpTimeoutMs` |
| Server node RPCs | `server/src/nodes.ts` | — | — | `server/src/config.ts` |

## Coordinate frame

Everything rendered lives in the sensor frame of `GAME_ROTATION_VECTOR`: origin = camera, z = up, x/y horizontal with an arbitrary zero direction. `HeadingEstimator` supplies the offset to true north outdoors; a home station's reference mark supplies it indoors.

## Persistence

| Data | Where | Synced |
|---|---|---|
| Public nodes | SQLite `nodes` | yes, via `NodeRepository` → `SyncEngine` |
| Home stations and pins | SQLite `home_station`, `home_pin` | never |
| Settings, device id, tutorial state | SharedPreferences `lobbyism` | never |

Schema changes: bump the version in `NodeStore`, add a step in `onUpgrade`, never edit an old step.

## Deliberate deviations from the Android guide

| Guide says | We do | Why |
|---|---|---|
| AAC ViewModel as state holder (strongly recommended) | Plain Kotlin state holders (`HomeController`, `TutorialController`) | No library dependencies (decision N1). The app is portrait-locked and handles config changes itself, so the ViewModel's main benefit, surviving recreation, does not apply. |
| Coroutines/Flow for observable state | `@Volatile` immutable snapshots polled each frame | The render loop already polls at 60 Hz; observers would add allocation and threads for no gain. |
| Jetpack Compose UI | Framework Views built in code | No dependencies; the UI is a thin overlay over the GL view. |
