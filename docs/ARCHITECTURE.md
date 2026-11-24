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
| Home base: stations, notes, carrying, floor placement | `core/HomeBase.kt` | `ui/home/HomeController.kt`, `ui/ActionButtons.kt` | warm colour + calm pulse in `ArRenderer` | `homePinDistanceM`, `homeNodeSizeM`, `homeGear*DistanceM` |
| Gear and stash (prototype stock) | `core/Gear.kt`, `data/HomeRepository.kt` | `ui/home/HomeController.kt` (stash dialogs), `ui/ContentCard.kt` | `render/GearGeometry.kt`, `ArRenderer.drawStand` | `gearStandHeightM`, `gearTierGlitch`, `stashSlots` |
| Start screen and modes (MENU, OUTDOOR, HOME) | — | `ui/MainActivity.kt` (mode switch), `ui/StartScreen.kt` | `ui/StartScreen.kt` | — |
| Room markers (AprilTag) | `core/CameraGeometry.kt`, `core/MarkerViews.kt` | `camera/MarkerTracker.kt`, `camera/MarkerNative.kt`, `cpp/lobbyism_marker.c`, `ui/GameLoop.currentMarkers` | `render/NodeGeometry.markerFrame` | `marker*`; measured size in Settings; print template `docs/marker/` |
| Leave a message outdoors | `data/NodeRepository.placeText` | `ui/outdoor/OutdoorController.kt` | `ui/ContentCard.kt` | `textMaxChars`, `placeMaxAccuracyM` |
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
| Home stations and objects (notes, armour stands) | SQLite `home_station`, `home_pin` (column `kind`) | never |
| Stash (gear items, prototype stock) | SQLite `gear_item` (mirrors the loot system's server model) | never; real gear will be server-authoritative (R4) |
| Settings, device id, tutorial state | SharedPreferences `lobbyism` | never |

Schema changes: bump the version in `NodeStore`, add a step in `onUpgrade`, never edit an old step.

Backups: `allowBackup=false` plus `res/xml/data_extraction_rules.xml` (Android 12+) and `res/xml/backup_rules.xml` (older) exclude all app data from cloud backup and device-to-device transfer, so private data cannot leave the phone that way either.

## Open lint warnings (accepted for now)

| Rule | Why accepted | Revisit when |
|---|---|---|
| SetTextI18n | UI text is German-only and built in code | adding a second language |
| ViewConstructor | Views are created in code, never from XML | — |
| LockedOrientationActivity, DiscouragedApi | AR view is portrait by design | tablet support |
| UnnecessaryRequiredFeature (GPS) | Outdoor play needs GPS; the home base alone would not | a home-only mode |
| OldTargetApi | targetSdk 35 needs AGP upgrade for 36 | before any store release |

## Deliberate deviations from the Android guide

| Guide says | We do | Why |
|---|---|---|
| AAC ViewModel as state holder (strongly recommended) | Plain Kotlin state holders (`HomeController`, `TutorialController`) | No library dependencies (decision N1). The app is portrait-locked and handles config changes itself, so the ViewModel's main benefit, surviving recreation, does not apply. |
| Coroutines/Flow for observable state | `@Volatile` immutable snapshots polled each frame | The render loop already polls at 60 Hz; observers would add allocation and threads for no gain. |
| Jetpack Compose UI | Framework Views built in code | No dependencies; the UI is a thin overlay over the GL view. |
