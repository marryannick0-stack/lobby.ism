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
import com.lobbyism.app.ui.theme.Theme
import com.lobbyism.app.ui.tutorial.TutorialController

/**
 * The app's single screen. Composition root and lifecycle owner only:
 * it builds every part, wires them together and starts/stops them with the
 * activity. No game logic lives here.
 *
 * Where things live:
 *   core/      pure logic, unit-tested       data/   repositories, storage, settings
 *   sensors/   gyro + GPS                    net/    server sync
 *   camera/    camera image                  render/ OpenGL engine
 *   feedback/  haptics, sound                ui/     this screen and its state holders
 */
class MainActivity : Activity() {

    private lateinit var cfg: Config
    private lateinit var settings: Settings
    private lateinit var store: NodeStore
    private lateinit var orientation: OrientationTracker
    private lateinit var location: LocationTracker
    private lateinit var camera: CameraFeed
    private lateinit var nodes: NodeRepository
    private lateinit var renderer: ArRenderer
    private lateinit var haptics: Haptics
    private lateinit var audio: SignalAudio

    private lateinit var dialogs: Dialogs
    private lateinit var permissions: PermissionGate
    private lateinit var home: HomeController
    private lateinit var tutorial: TutorialController
    private lateinit var loop: GameLoop
    private lateinit var buttons: ActionButtons
    private lateinit var card: ContentCard
    private lateinit var glView: GLSurfaceView

    private val main = Handler(Looper.getMainLooper())
    private val pose = PoseCache()
    private var resumed = false
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
        camera = CameraFeed(this)
        haptics = Haptics(this)
        audio = SignalAudio()

        // --- UI state holders ---
        dialogs = Dialogs(this) { enterImmersiveMode() }
        permissions = PermissionGate(this, settings, dialogs) { if (resumed) startPermissionGated() }
        home = HomeController(cfg, HomeRepository(store), pose, dialogs) { onHomeChanged() }
        tutorial = TutorialController(this, cfg.tutorial, settings, onRotateCamera = { rotateCamera() }) { renderButtons() }

        // --- engine ---
        val hud = HudView(this) // created before rendering starts: the GL thread writes into it
        loop = GameLoop(cfg, orientation, location, nodes, home, tutorial, pose, hud) { node -> card.show(node) }
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

        permissions.begin()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        enterImmersiveMode()
        glView.onResume()
        startSensorsAndCamera()
        nodes.startSync()
        permissions.refresh()
    }

    override fun onPause() {
        resumed = false
        nodes.stopSync()
        camera.close()
        location.stop()
        orientation.stop()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        SensoryFeedback.removeListener(haptics)
        SensoryFeedback.removeListener(audio)
        SensoryFeedback.removeListener(renderer)
        card.close()
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

    // ---------------------------------------------------------------- screen

    /** Layers from back to front: camera + world, HUD, cards, buttons, permission notice. */
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

        card = ContentCard(this) { pinId -> home.onPinTapped(pinId) { card.close() } }
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
            onMain = { if (home.isActive) home.onMainAction() else askForText() },
            onHome = { home.toggle() },
            onHomeLong = { if (home.isActive) home.manage() },
            onAlign = { home.align() },
            onSettings = {
                SettingsDialog(this, settings, nodes, dialogs, ::rotateCamera) {
                    card.close()
                    tutorial.start()
                }.show()
            },
        )
        buttons.addTo(root)
        renderButtons()

        root.addView(permissions.blocker, Theme.matchParent())
        setContentView(root)
    }

    private fun renderButtons() = buttons.render(home.ui, tutorial.highlightPlace)

    private fun onHomeChanged() {
        card.close()
        tutorial.setSuspended(home.isActive)
        renderButtons()
    }

    // ----------------------------------------------------------------- nodes

    private fun askForText() {
        val fix = location.fix?.takeIf { it.isFresh() }
        if (fix == null) {
            SensoryFeedback.emit(SensoryFeedback.Cue.DENIED)
            dialogs.toast("Kein Signal. Ohne Position kann nichts hinterlassen werden.")
            return
        }
        val accuracyNote = if (fix.accuracyM > cfg.placeMaxAccuracyM) {
            "\n\nSignal ungenau (±${fix.accuracyM.toInt()} m). Die Nachricht landet evtl. ein Stück daneben."
        } else {
            ""
        }
        dialogs.textInput(
            title = "Hier hinterlassen",
            message = "Deine Nachricht bleibt an diesem Ort.$accuracyNote",
            hint = "Was bleibt hier?",
            maxChars = cfg.textMaxChars,
            confirm = "Ablegen",
        ) { text -> placeText(text) }
    }

    private fun placeText(text: String) {
        // Position at the moment of confirming, not of opening the dialog: the player may have walked on.
        val fix = location.fix?.takeIf { it.isFresh() }
        if (text.isEmpty() || fix == null) {
            SensoryFeedback.emit(SensoryFeedback.Cue.DENIED)
            dialogs.toast(if (text.isEmpty()) "Leere Nachricht." else "Signal verloren. Nichts abgelegt.")
            return
        }
        val node = nodes.placeText(text, fix.lat, fix.lon)
        SensoryFeedback.emit(SensoryFeedback.Cue.NODE_PLACED)
        tutorial.onNodePlaced(node.id)
    }

    // ------------------------------------------------------ hardware control

    private fun startSensorsAndCamera() {
        orientation.start()
        startPermissionGated()
    }

    /** GPS and camera need runtime permissions; safe to call again after they were granted. */
    private fun startPermissionGated() {
        if (permissions.hasLocation) location.start()
        openCameraIfPossible()
    }

    private fun openCameraIfPossible() {
        val texture = cameraTexture ?: return
        if (!resumed || !permissions.hasCamera) return
        camera.open(texture) { message -> main.post { dialogs.toast(message) } }
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
