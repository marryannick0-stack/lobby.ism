package com.lobbyism.app.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lobbyism.app.camera.CameraFeed
import com.lobbyism.app.core.Config
import com.lobbyism.app.core.ContentStatus
import com.lobbyism.app.core.ContentType
import com.lobbyism.app.core.HeadingEstimator
import com.lobbyism.app.core.Node
import com.lobbyism.app.core.WorldTracker
import com.lobbyism.app.data.ConfigLoader
import com.lobbyism.app.data.NodeStore
import com.lobbyism.app.feedback.Haptics
import com.lobbyism.app.feedback.SensoryFeedback
import com.lobbyism.app.feedback.SignalAudio
import com.lobbyism.app.net.Settings
import com.lobbyism.app.net.SyncEngine
import com.lobbyism.app.render.ArRenderer
import com.lobbyism.app.sensors.LocationTracker
import com.lobbyism.app.sensors.OrientationTracker
import java.util.UUID

/**
 * The one screen of Phase 0a: camera, world layer, HUD.
 *
 * This class only wires the parts together and owns the lifecycle:
 *   sensors + GPS  ->  WorldTracker (what is where)  ->  ArRenderer (draws it)
 *                                   \->  SensoryFeedback (haptics, sound, glitch)
 *   NodeStore (phone)  <->  SyncEngine  <->  server
 */
class MainActivity : Activity(), ArRenderer.Scene {

    private lateinit var cfg: Config
    private lateinit var settings: Settings
    private lateinit var store: NodeStore
    private lateinit var orientation: OrientationTracker
    private lateinit var location: LocationTracker
    private lateinit var camera: CameraFeed
    private lateinit var renderer: ArRenderer
    private lateinit var tracker: WorldTracker
    private lateinit var heading: HeadingEstimator
    private lateinit var sync: SyncEngine
    private lateinit var haptics: Haptics
    private lateinit var audio: SignalAudio

    private lateinit var glView: GLSurfaceView
    private lateinit var hud: HudView
    private lateinit var card: TextView
    private lateinit var placeButton: Button
    private lateinit var blocker: LinearLayout
    private lateinit var blockerText: TextView

    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var cardAnimator: ValueAnimator? = null

    // --- state shared between the main thread and the GL thread ---
    @Volatile private var nodes: List<Node> = emptyList()
    @Volatile private var headingOffsetDeg: Double? = null
    @Volatile private var headingConfidence = 0f
    @Volatile private var lastSensorAzimuthDeg: Double? = null
    @Volatile private var cameraTexture: SurfaceTexture? = null
    @Volatile private var syncStatus = SyncEngine.Status.LOCAL_ONLY
    @Volatile private var unsyncedCount = 0

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        cfg = ConfigLoader.load(this)
        settings = Settings(this)
        store = NodeStore(this)
        orientation = OrientationTracker(this)
        location = LocationTracker(this, cfg)
        camera = CameraFeed(this)
        tracker = WorldTracker(cfg)
        heading = HeadingEstimator(
            cfg.headingMinSpeedMps, cfg.headingMaxCourseAccuracyDeg, cfg.headingSamplesNeeded, cfg.headingSmoothing,
        )
        haptics = Haptics(this)
        audio = SignalAudio()

        renderer = ArRenderer(cfg, orientation, camera.previewSize, camera.fovLongSideRad, this) { texture ->
            // GL thread: a (new) camera texture exists. Open the camera from the main thread.
            cameraTexture = texture
            main.post { openCameraIfPossible() }
        }
        renderer.cameraRotationSteps = settings.cameraRotationSteps

        sync = SyncEngine(
            cfg, store, settings,
            position = { location.fix?.takeIf { it.isFresh() }?.let { doubleArrayOf(it.lat, it.lon) } },
            onResult = { status, changed ->
                syncStatus = status
                if (changed) reloadNodes()
            },
        )

        location.onFix = { fix -> onGpsFix(fix) }

        buildUi()
        reloadNodes()

        SensoryFeedback.addListener(haptics)
        SensoryFeedback.addListener(audio)
        SensoryFeedback.addListener(renderer)

        if (settings.introSeen) requestMissingPermissions() else showIntro()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        enterImmersiveMode()
        glView.onResume()
        orientation.start()
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) location.start()
        openCameraIfPossible()
        sync.start()
        updateBlocker()
    }

    override fun onPause() {
        resumed = false
        sync.stop()
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
        cardAnimator?.cancel()
        audio.release()
        store.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    @Suppress("DEPRECATION") // WindowInsetsController needs API 30; these flags work on every supported version.
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    // ------------------------------------------------- game loop (GL thread)

    /** Called by the renderer once per frame. Runs on the GL thread. */
    override fun frame(dtS: Float, poseValid: Boolean, forward: FloatArray, top: FloatArray): List<WorldTracker.NodeView> {
        val fix = location.fix?.takeIf { it.isFresh() }
        if (poseValid) {
            lastSensorAzimuthDeg = WorldTracker.sensorAzimuthDeg(WorldTracker.horizontalForward(forward, top))
        }

        val frame = if (poseValid) {
            tracker.update(dtS, forward, top, fix?.lat, fix?.lon, fix?.accuracyM ?: 99.0, headingOffsetDeg, nodes)
        } else {
            null
        }

        frame?.events?.forEach { e ->
            when (e) {
                is WorldTracker.Event.Materialized -> SensoryFeedback.emit(SensoryFeedback.Cue.NODE_MATERIALIZED)
                is WorldTracker.Event.LockTick -> SensoryFeedback.emit(SensoryFeedback.Cue.LOCK_TICK, e.progress)
                is WorldTracker.Event.Locked -> {
                    SensoryFeedback.emit(SensoryFeedback.Cue.LOCK_COMPLETE)
                    main.post { showCard(e.node) }
                }
                is WorldTracker.Event.LockLost -> SensoryFeedback.emit(SensoryFeedback.Cue.LOCK_LOST, 0.5f)
            }
        }

        hud.state = HudView.State(
            gpsEnabled = location.isGpsEnabled,
            hasFix = fix != null,
            accuracyM = fix?.accuracyM ?: 0.0,
            headingReady = headingOffsetDeg != null,
            headingConfidence = headingConfidence,
            nodesInRange = (frame?.views?.size ?: 0) + (frame?.hiddenUntilHeading ?: 0),
            nearestM = frame?.nearestDistanceM,
            hiddenUntilHeading = frame?.hiddenUntilHeading ?: 0,
            sync = syncStatus,
            unsynced = unsyncedCount,
            focusLock = frame?.focus?.lock,
            focusRevealed = frame?.focus?.revealed ?: false,
            sensorOk = orientation.isAvailable,
        )
        return frame?.views ?: emptyList()
    }

    /** Main thread: every GPS fix may teach the heading estimator where north is. */
    private fun onGpsFix(fix: LocationTracker.Fix) {
        val course = fix.courseDeg
        val azimuth = lastSensorAzimuthDeg
        if (course != null && azimuth != null) {
            heading.addSample(course, azimuth, fix.speedMps, fix.courseAccuracyDeg)
            headingOffsetDeg = if (heading.isReady) heading.offsetDeg else null
            headingConfidence = heading.confidence.toFloat()
        }
    }

    // ----------------------------------------------------------------- nodes

    private fun reloadNodes() {
        val all = store.all()
        nodes = all
        unsyncedCount = all.count { !it.synced }
    }

    private fun askForText() {
        val fix = location.fix?.takeIf { it.isFresh() }
        if (fix == null) {
            SensoryFeedback.emit(SensoryFeedback.Cue.DENIED)
            toast("Kein Signal. Ohne Position kann nichts hinterlassen werden.")
            return
        }
        val input = EditText(this).apply {
            hint = "Was bleibt hier?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(cfg.textMaxChars))
            minLines = 3
            gravity = Gravity.TOP
        }
        val pad = dp(20)
        val box = FrameLayout(this).apply {
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        val accuracyNote = if (fix.accuracyM > cfg.placeMaxAccuracyM) {
            "\n\nSignal ungenau (±${fix.accuracyM.toInt()} m). Die Nachricht landet evtl. ein Stück daneben."
        } else {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle("Hier hinterlassen")
            .setMessage("Deine Nachricht bleibt an diesem Ort.$accuracyNote")
            .setView(box)
            .setPositiveButton("Ablegen") { _, _ -> placeText(input.text.toString()) }
            .setNegativeButton("Abbrechen", null)
            .setOnDismissListener { enterImmersiveMode() }
            .show()
    }

    private fun placeText(raw: String) {
        val textValue = raw.trim()
        val fix = location.fix?.takeIf { it.isFresh() }
        if (textValue.isEmpty() || fix == null) {
            SensoryFeedback.emit(SensoryFeedback.Cue.DENIED)
            if (textValue.isEmpty()) toast("Leere Nachricht.") else toast("Signal verloren. Nichts abgelegt.")
            return
        }
        store.insertLocal(
            Node(
                id = UUID.randomUUID().toString(),
                lat = fix.lat,
                lon = fix.lon,
                contentType = ContentType.TEXT,
                payload = textValue.take(cfg.textMaxChars),
                ownerId = null,
                status = ContentStatus.LIVE,
                createdAtMs = System.currentTimeMillis(),
                synced = false,
            ),
        )
        reloadNodes()
        SensoryFeedback.emit(SensoryFeedback.Cue.NODE_PLACED)
        sync.requestSync()
    }

    // ------------------------------------------------------------------- UI

    private fun buildUi() {
        val root = FrameLayout(this)

        // Created first: the GL thread writes into it as soon as rendering starts.
        hud = HudView(this)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glView, matchParent())
        root.addView(hud, matchParent())

        // Content card: appears when a lock completes, closes on tap.
        card = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 15f
            setTextColor(PHOSPHOR)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = panel()
            visibility = View.GONE
            setOnClickListener { hideCard() }
        }
        root.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply { setMargins(dp(14), 0, dp(14), dp(96)) },
        )

        placeButton = Button(this).apply {
            text = "+ HINTERLASSEN"
            styleAsSignalButton(this)
            setOnClickListener { askForText() }
        }
        root.addView(
            placeButton,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(30) },
        )

        val settingsButton = Button(this).apply {
            text = "≡"
            styleAsSignalButton(this)
            textSize = 20f
            contentDescription = "Einstellungen"
            setOnClickListener { showSettings() }
        }
        root.addView(
            settingsButton,
            FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END)
                .apply { setMargins(0, 0, dp(14), dp(30)) },
        )

        // Full-screen notice while a required permission is missing.
        blocker = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF0000000.toInt())
            setPadding(dp(28), dp(28), dp(28), dp(28))
            visibility = View.GONE
            isClickable = true // swallow touches meant for the views below
        }
        blockerText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 15f
            setTextColor(PHOSPHOR)
            gravity = Gravity.CENTER
        }
        val blockerButton = Button(this).apply {
            text = "ZUGRIFF ERLAUBEN"
            styleAsSignalButton(this)
            setOnClickListener { onBlockerAction() }
        }
        blocker.addView(blockerText)
        blocker.addView(
            blockerButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { topMargin = dp(24) },
        )
        root.addView(blocker, matchParent())

        setContentView(root)
    }

    private fun showCard(node: Node) {
        val age = DateUtils.getRelativeTimeSpanString(
            node.createdAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        )
        val origin = if (node.synced) "SERVER" else "NUR AUF DIESEM GERÄT"
        val full = "SIGNAL // TEXT\n\n${node.payload}\n\n$age · $origin\n[ TIPPEN ZUM SCHLIESSEN ]"

        card.visibility = View.VISIBLE
        cardAnimator?.cancel()
        // The text arrives character by character, like a transmission coming in.
        cardAnimator = ValueAnimator.ofInt(0, full.length).apply {
            duration = (full.length * 9L).coerceIn(250L, 1400L)
            addUpdateListener { card.text = full.substring(0, it.animatedValue as Int) }
            start()
        }
    }

    private fun hideCard() {
        cardAnimator?.cancel()
        card.visibility = View.GONE
    }

    private fun showSettings() {
        val url = EditText(this).apply {
            hint = "https://dein-server:7350"
            setText(settings.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val key = EditText(this).apply {
            hint = "Server-Key"
            setText(settings.serverKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val info = TextView(this).apply {
            val all = store.all()
            text = "Nodes auf diesem Gerät: ${all.size} (${all.count { !it.synced }} nicht hochgeladen)\n" +
                "Geräte-ID: ${settings.deviceId.take(8)}…\n" +
                "Ohne Server-Adresse bleibt alles lokal auf diesem Gerät."
            setPadding(0, dp(12), 0, 0)
        }
        val rotate = Button(this).apply {
            text = "Kamerabild um 90° drehen"
            setOnClickListener {
                settings.cameraRotationSteps = settings.cameraRotationSteps + 1
                renderer.cameraRotationSteps = settings.cameraRotationSteps
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(url)
            addView(key)
            addView(info)
            addView(rotate)
        }
        AlertDialog.Builder(this)
            .setTitle("Einstellungen")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                settings.serverUrl = url.text.toString()
                settings.serverKey = key.text.toString()
                sync.requestSync()
            }
            .setNegativeButton("Schließen", null)
            .setOnDismissListener { enterImmersiveMode() }
            .show()
    }

    // ----------------------------------------------------------- permissions

    private fun showIntro() {
        AlertDialog.Builder(this)
            .setTitle("LOBBYISM")
            .setMessage(
                "Diese App legt Inhalte über die echte Welt.\n\n" +
                    "Dafür braucht sie:\n" +
                    "• Kamera: zeigt die Umgebung. Das Bild wird nicht gespeichert und nicht übertragen.\n" +
                    "• Standort: verankert Inhalte an echten Orten. Deine Position wird nur gesendet, " +
                    "wenn du selbst einen Server einträgst.",
            )
            .setCancelable(false)
            .setPositiveButton("Weiter") { _, _ ->
                settings.introSeen = true
                requestMissingPermissions()
            }
            .show()
    }

    private fun requestMissingPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter { !hasPermission(it) }
        if (missing.isNotEmpty()) requestPermissions(withCoarse(missing), PERMISSION_REQUEST)
        updateBlocker()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        if (resumed) {
            if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) location.start()
            openCameraIfPossible()
        }
        updateBlocker()
    }

    private fun updateBlocker() {
        val missing = REQUIRED_PERMISSIONS.filter { !hasPermission(it) }
        if (missing.isEmpty() || !settings.introSeen) {
            blocker.visibility = View.GONE
            return
        }
        val names = missing.joinToString(" und ") {
            if (it == Manifest.permission.CAMERA) "Kamera" else "genauen Standort"
        }
        blockerText.text = "Ohne $names funktioniert LOBBYISM nicht."
        blocker.visibility = View.VISIBLE
    }

    private fun onBlockerAction() {
        val missing = REQUIRED_PERMISSIONS.filter { !hasPermission(it) }
        // After "don't ask again" Android no longer shows the dialog: send the player to the app settings instead.
        val permanentlyDenied = missing.any { !shouldShowRequestPermissionRationale(it) }
        if (permanentlyDenied) {
            startActivity(
                Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        } else {
            requestPermissions(withCoarse(missing), PERMISSION_REQUEST)
        }
    }

    /**
     * Android 12+ ignores a request for the precise location unless the
     * approximate one is requested in the same dialog. The app still needs the
     * precise one: GPS is not available with approximate location only.
     */
    private fun withCoarse(missing: List<String>): Array<String> =
        if (Manifest.permission.ACCESS_FINE_LOCATION in missing) {
            (missing + Manifest.permission.ACCESS_COARSE_LOCATION).toTypedArray()
        } else {
            missing.toTypedArray()
        }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun openCameraIfPossible() {
        val texture = cameraTexture ?: return
        if (!resumed || !hasPermission(Manifest.permission.CAMERA)) return
        camera.open(texture) { message -> main.post { toast(message) } }
    }

    // --------------------------------------------------------------- helpers

    private fun styleAsSignalButton(b: Button) {
        b.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        b.setTextColor(PHOSPHOR)
        b.textSize = 14f
        b.letterSpacing = 0.08f
        b.isAllCaps = false
        b.background = panel()
        b.setPadding(dp(18), 0, dp(18), 0)
        b.minWidth = 0
        b.minimumWidth = 0
        b.stateListAnimator = null
    }

    /** Dark, slightly see-through panel with a thin phosphor outline. */
    private fun panel() = GradientDrawable().apply {
        setColor(0xD9050A08.toInt())
        setStroke(dp(1), 0xB3CDFFDB.toInt())
        cornerRadius = 0f
    }

    private fun matchParent() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val PERMISSION_REQUEST = 1
        const val PHOSPHOR = 0xFFCDFFDB.toInt()
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
