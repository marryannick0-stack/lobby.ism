package com.lobbyism.app.ui

import android.app.Activity
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.lobbyism.app.camera.CameraFeed
import com.lobbyism.app.camera.MarkerTracker
import com.lobbyism.app.core.Config
import com.lobbyism.app.data.ConfigLoader
import com.lobbyism.app.data.HomeRepository
import com.lobbyism.app.data.NodeRepository
import com.lobbyism.app.data.NodeStore
import com.lobbyism.app.data.Settings
import com.lobbyism.app.feedback.Haptics
import com.lobbyism.app.feedback.SensoryFeedback
import com.lobbyism.app.feedback.SignalAudio
import com.lobbyism.app.render.ArRenderer
import com.lobbyism.app.sensors.LocationTracker
import com.lobbyism.app.sensors.OrientationTracker
import com.lobbyism.app.ui.home.HomeController
import com.lobbyism.app.ui.outdoor.OutdoorController
import com.lobbyism.app.ui.theme.Theme
import com.lobbyism.app.ui.tutorial.TutorialController

/**
 * The app's single screen. Composition root and lifecycle owner only:
 * it builds every part, wires them together, switches modes and starts/stops
 * hardware with the activity. No game logic lives here.
 *
 * Modes: MENU (start screen, camera and GPS off), OUTDOOR, HOME.
 *
 * Where things live:
 *   core/      pure logic, unit-tested       data/   repositories, storage, settings
 *   sensors/   gyro + GPS                    net/    server sync
 *   camera/    camera image                  render/ OpenGL engine
 *   feedback/  haptics, sound                ui/     this screen and its state holders
 */
class MainActivity : Activity() {

    private enum class Mode { MENU, OUTDOOR, HOME }

    private lateinit var cfg: Config
    private lateinit var settings: Settings
    private lateinit var store: NodeStore
    private lateinit var orientation: OrientationTracker
    private lateinit var location: LocationTracker
    private lateinit var camera: CameraFeed
    private lateinit var markers: MarkerTracker
    private lateinit var nodes: NodeRepository
    private lateinit var renderer: ArRenderer
    private lateinit var haptics: Haptics
    private lateinit var audio: SignalAudio

    private lateinit var dialogs: Dialogs
    private lateinit var permissions: PermissionGate
    private lateinit var home: HomeController
    private lateinit var tutorial: TutorialController
    private lateinit var outdoor: OutdoorController
    private lateinit var loop: GameLoop
    private lateinit var buttons: ActionButtons
    private lateinit var card: ContentCard
    private lateinit var startScreen: StartScreen
    private lateinit var glView: GLSurfaceView

    private val main = Handler(Looper.getMainLooper())
    private val pose = PoseCache()
    private var resumed = false
    private var mode = Mode.MENU
    /** GLSurfaceView has no "is paused" getter; tracked here so pause/resume stay balanced. */
    private var glRunning = true
    @Volatile private var cameraTexture: SurfaceTexture? = null

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // --- data layer ---
        cfg = ConfigLoader.load(this)
        settings = Settings(this)
        store = NodeStore(this)
        location = LocationTracker(this, cfg)
        nodes = NodeRepository(
            cfg, store, settings,
            position = { location.fix?.takeIf { it.isFresh() }?.let { doubleArrayOf(it.lat, it.lon) } },
        )

        // --- sensors, camera, feedback ---
        orientation = OrientationTracker(this)
        camera = CameraFeed(this, cfg.markerAnalysisWidth)
        markers = MarkerTracker(cfg, camera, orientation).apply {
            settings.markerSizeCm.takeIf { it > 0f }?.let { markerSizeM = it / 100.0 }
        }
        haptics = Haptics(this)
        audio = SignalAudio()

        // --- UI state holders ---
        dialogs = Dialogs(this) { enterImmersiveMode() }
        permissions = PermissionGate(this, settings, dialogs) { if (resumed && mode != Mode.MENU) startPermissionGated() }
        home = HomeController(cfg, HomeRepository(cfg, store), pose, dialogs) { onHomeChanged() }
        tutorial = TutorialController(this, cfg.tutorial, settings, onRotateCamera = { rotateCamera() }) { renderButtons() }
        outdoor = OutdoorController(cfg, nodes, location, dialogs) { id -> tutorial.onNodePlaced(id) }

        // --- engine ---
        val hud = HudView(this) // created before rendering starts: the GL thread writes into it
        loop = GameLoop(cfg, orientation, location, nodes, home, tutorial, pose, markers, hud) { node -> card.show(node) }
        renderer = ArRenderer(cfg, orientation, camera.previewSize, camera.fovLongSideRad, loop) { texture ->
            // GL thread: a (new) camera texture exists. Open the camera from the main thread.
            cameraTexture = texture
            main.post { openCameraIfPossible() }
        }
        renderer.cameraRotationSteps = settings.cameraRotationSteps
        location.onFix = loop::onGpsFix

        buildScreen(hud)

        SensoryFeedback.addListener(haptics)
        SensoryFeedback.addListener(audio)
        SensoryFeedback.addListener(renderer)

        showMenu()
        permissions.begin()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        enterImmersiveMode()
        if (mode != Mode.MENU) startWorld()
        nodes.startSync()
        permissions.refresh()
    }

    override fun onPause() {
        resumed = false
        nodes.stopSync()
        if (mode != Mode.MENU) stopWorld()
        super.onPause()
    }

    override fun onDestroy() {
        SensoryFeedback.removeListener(haptics)
        SensoryFeedback.removeListener(audio)
        SensoryFeedback.removeListener(renderer)
        card.close()
        markers.release()
        audio.release()
        store.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        this.permissions.onResult(requestCode)
    }

    @Deprecated("Deprecated in Android 13; still the hook for API 26-32")
    override fun onBackPressed() {
        // Back from a mode returns to the start screen; on the start screen it closes the app.
        if (mode != Mode.MENU) showMenu() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ----------------------------------------------------------------- modes

    private fun showMenu() {
        val wasActive = mode != Mode.MENU
        mode = Mode.MENU
        card.close()
        home.exit()
        tutorial.setSuspended(true)
        // The renderer runs from the moment the view is attached; on the menu nothing needs it.
        if (wasActive && resumed) stopWorld() else pauseRendering()
        startScreen.render(settings.tutorialDone, buildLabel())
        startScreen.visibility = View.VISIBLE
    }

    private fun startOutdoor(withTutorial: Boolean) {
        enterMode(Mode.OUTDOOR)
        if (withTutorial) tutorial.start() else tutorial.setSuspended(false)
    }

    private fun startHome() {
        enterMode(Mode.HOME)
        home.enterFromMenu(onCancel = ::showMenu)
    }

    private fun enterMode(next: Mode) {
        mode = next
        startScreen.visibility = View.GONE
        if (resumed) startWorld()
        renderButtons()
    }

    /** Camera, sensors and rendering on. GPS only outdoors: the home base does not need a position. */
    private fun startWorld() {
        if (!glRunning) {
            glView.onResume()
            glRunning = true
        }
        orientation.start()
        startPermissionGated()
    }

    private fun stopWorld() {
        camera.close()
        markers.stop() // after the camera: its session used the marker surface
        location.stop()
        orientation.stop()
        pauseRendering()
    }

    private fun pauseRendering() {
        if (glRunning) {
            glView.onPause()
            glRunning = false
        }
    }

    // ---------------------------------------------------------------- screen

    /** Layers from back to front: camera + world, HUD, cards, buttons, start screen, permission notice. */
    private fun buildScreen(hud: HudView) {
        val root = FrameLayout(this)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glView, Theme.matchParent())
        root.addView(hud, Theme.matchParent())

        card = ContentCard(
            this,
            onHomeObjectTapped = { id -> home.onObjectTapped(id) { card.close() } },
            describeHomeObject = home::describe,
        )
        root.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply { setMargins(dp(Theme.EDGE_DP), 0, dp(Theme.EDGE_DP), dp(CARD_BOTTOM_DP)) },
        )

        root.addView(
            tutorial.overlay,
            // Below the HUD status lines, above the camera centre.
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
                .apply { setMargins(dp(Theme.EDGE_DP), dp(TUTORIAL_TOP_DP), dp(Theme.EDGE_DP), 0) },
        )

        buttons = ActionButtons(
            this,
            onMain = { if (mode == Mode.HOME) home.onMainAction() else outdoor.askForText() },
            onMenu = ::showMenu,
            onMenuLong = { if (mode == Mode.HOME) home.manage() },
            onSecondary = { home.onSecondaryAction() },
            onSettings = ::showSettings,
        )
        buttons.addTo(root)
        renderButtons()

        startScreen = StartScreen(
            this,
            onOutdoor = { startOutdoor(withTutorial = false) },
            onHome = ::startHome,
            onTutorial = { startOutdoor(withTutorial = true) },
            onSettings = ::showSettings,
        )
        root.addView(startScreen, Theme.matchParent())

        root.addView(permissions.blocker, Theme.matchParent())
        setContentView(root)
    }

    private fun showSettings() {
        SettingsDialog(
            this, settings, nodes, dialogs, ::rotateCamera,
            onRestartTutorial = {
                card.close()
                startOutdoor(withTutorial = true)
            },
            defaultMarkerCm = cfg.markerSizeM * 100.0,
            onMarkerSize = { cm -> markers.markerSizeM = cm / 100.0 },
        ).show()
    }

    private fun renderButtons() = buttons.render(home.ui, tutorial.highlightPlace)

    private fun onHomeChanged() {
        card.close()
        renderButtons()
    }

    private fun buildLabel(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    // ------------------------------------------------------ hardware control

    /** GPS and camera need runtime permissions; safe to call again after they were granted. */
    private fun startPermissionGated() {
        if (mode == Mode.OUTDOOR && permissions.hasLocation) location.start() else location.stop()
        openCameraIfPossible()
    }

    private fun openCameraIfPossible() {
        val texture = cameraTexture ?: return
        if (!resumed || mode == Mode.MENU || !permissions.hasCamera) return
        // Home base: a second grey stream feeds the room-marker detector.
        val analysis = if (mode == Mode.HOME) markers.start() else null
        camera.open(texture, analysis) { message -> main.post { dialogs.toast(message) } }
    }

    private fun rotateCamera() {
        settings.cameraRotationSteps = settings.cameraRotationSteps + 1
        renderer.cameraRotationSteps = settings.cameraRotationSteps
    }

    @Suppress("DEPRECATION") // WindowInsetsController needs API 30; these flags work on every supported version.
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    private fun dp(v: Int) = Theme.dp(this, v)

    private companion object {
        const val CARD_BOTTOM_DP = 96
        const val TUTORIAL_TOP_DP = 128
    }
}
