package com.omoai.simpleuvcstreamer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.omoai.simpleuvcstreamer.facetrack.FaceTrackProtocol
import com.omoai.simpleuvcstreamer.preview.FramePreviewController
import com.omoai.simpleuvcstreamer.service.BatteryKeepAlive
import com.omoai.simpleuvcstreamer.service.CaptureKeepAlive
import com.omoai.simpleuvcstreamer.service.CaptureService
import com.omoai.simpleuvcstreamer.stream.HttpStreamController
import com.omoai.simpleuvcstreamer.stream.NetworkAddresses
import com.omoai.simpleuvcstreamer.ui.SafeArea
import com.omoai.simpleuvcstreamer.usb.UsbAutoLaunch
import com.omoai.simpleuvcstreamer.usb.UvcDeviceFinder
import com.omoai.simpleuvcstreamer.util.AppPermissions
import com.omoai.simpleuvcstreamer.util.FileLogger
import com.omoai.simpleuvcstreamer.util.PermissionRationale
import com.omoai.simpleuvcstreamer.uvc.AutoStartPrefs
import com.omoai.simpleuvcstreamer.uvc.StreamMode
import com.omoai.simpleuvcstreamer.uvc.UvcNative

/**
 * UI only. USB capture and HTTP live in [CaptureService] so leaving this
 * screen does not tear down an active stream.
 *
 * Two camera slots are shown: each has its own device / resolution / fps,
 * preview and start switch, plus its own HTTP port and reachable URLs.
 */
class MainActivity : AppCompatActivity(), CaptureService.Listener {

    /** All views belonging to one camera slot. */
    private inner class SlotUi(val index: Int) {
        lateinit var title: TextView
        lateinit var tvStatus: TextView
        lateinit var tvFps: TextView
        lateinit var dropdownDevice: AutoCompleteTextView
        lateinit var layoutResolution: TextInputLayout
        lateinit var dropdownResolution: AutoCompleteTextView
        lateinit var layoutFps: TextInputLayout
        lateinit var dropdownFps: AutoCompleteTextView
        lateinit var switchStream: MaterialSwitch
        lateinit var switchPreview: MaterialSwitch
        lateinit var previewContainer: MaterialCardView
        lateinit var imagePreview: ImageView
        lateinit var streamTitle: TextView
        lateinit var editHttpPort: TextInputEditText
        lateinit var btnApplyHttpPort: MaterialButton
        lateinit var tvHttpState: TextView
        lateinit var listAccessUrls: LinearLayout
        lateinit var tvHttpNoUrls: TextView
        lateinit var previewController: FramePreviewController

        var suppressDeviceCallback = false
        var suppressResolutionCallback = false
        var suppressFpsCallback = false
        var lastAccessSignature: String = ""
        var lastHttpStateText: String = ""
        var lastDeviceSig: String = ""
        var lastModeSig: String = ""

        /**
         * deviceName of the camera currently shown in this slot's dropdown.
         * Resolving the camera by deviceName (not by the displayed label) keeps
         * two identical cameras selectable — labels/names are not unique.
         */
        var selectedDeviceName: String? = null
    }

    private val slotUis: Array<SlotUi> = Array(UvcNative.CAMERA_COUNT) { SlotUi(it) }

    private lateinit var switchAutoStart: MaterialSwitch
    private lateinit var switchAutoLaunch: MaterialSwitch
    private lateinit var switchBattery: MaterialSwitch

    // 面捕眼追软件直连（眼追/面捕 DIY 摄像头协议）
    private lateinit var switchFaceTrack: MaterialSwitch
    private lateinit var tvFaceTrackStatus: TextView
    private lateinit var dropdownFaceTrackFlag1: AutoCompleteTextView
    private lateinit var dropdownFaceTrackFlag2: AutoCompleteTextView
    private var suppressFaceTrackFlagCallback = false

    /** Last flag pushed into each position dropdown; avoids re-setting it every tick. */
    private val faceTrackFlagShown =
        IntArray(UvcNative.CAMERA_COUNT) { Int.MIN_VALUE }

    private var capture: CaptureService? = null
    private var bound = false
    private var pendingAttachDevice: UsbDevice? = null
    private var uiResumed = false
    private var permissionDialogShowing = false
    private var keepAlivePromptedThisSession = false
    private var batterySkippedThisSession = false
    private var pendingBatteryAfterPerms = false
    private var pendingAfterKeepAlive: (() -> Unit)? = null

    /**
     * Quest / Horizon OS kills the process when the UsbAttachAlias that cold-started us
     * is disabled (DONT_KILL_APP is ignored). Never disable that alias for this instance.
     */
    private var launchedViaUsbAttach = false

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            FileLogger.log("Runtime permissions: $result")
            val denied = result.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                FileLogger.log("Permissions denied: $denied (MJPEG video can still work)")
            }
            continueKeepAliveAfterPermissions()
        }

    private val batteryExemptionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            FileLogger.log("Battery exemption back, exempt=${BatteryKeepAlive.isExempt(this)}")
            syncBatterySwitch()
            finishKeepAliveChain()
        }

    private val fpsHandler = Handler(Looper.getMainLooper())
    private val fpsRunnable = object : Runnable {
        override fun run() {
            val session = capture
            if (session != null && UvcNative.isLibLoaded) {
                slotUis.forEach { ui ->
                    val snapshot = session.snapshot(ui.index)
                    if (snapshot.streaming) {
                        updateFpsText(ui, session.frameCountPerSecond(ui.index))
                    } else {
                        ui.tvFps.visibility = View.GONE
                    }
                }
            } else {
                slotUis.forEach { ui -> ui.tvFps.visibility = View.GONE }
            }
            refreshHttpUi(forceList = false)
            renderFaceTrack()
            fpsHandler.postDelayed(this, 1000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as CaptureService.LocalBinder).service()
            capture = service
            bound = true
            service.addListener(this@MainActivity)
            pendingAttachDevice?.let {
                pendingAttachDevice = null
                service.onUsbAttachIntent(it)
            }
            FileLogger.log("CaptureService connected")
            maybePromptKeepAlive()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            capture?.removeListener(this@MainActivity)
            capture = null
            bound = false
            FileLogger.log("CaptureService disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SafeArea.apply(this, R.id.main)
        FileLogger.log("--- App Started ---")
        launchedViaUsbAttach =
            intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        if (launchedViaUsbAttach) {
            FileLogger.log("Cold start via USB_DEVICE_ATTACHED — will not disable UsbAttachAlias")
        }

        switchAutoStart = findViewById(R.id.switchAutoStart)
        switchAutoLaunch = findViewById(R.id.switchAutoLaunch)
        switchBattery = findViewById(R.id.switchBattery)

        slotUis.forEach { bindSlot(it) }

        if (!UvcNative.isLibLoaded) {
            slotUis.forEach { it.tvStatus.text = getString(R.string.status_lib_missing) }
            FileLogger.log("FATAL: Library not loaded")
        }

        switchAutoStart.isChecked = AutoStartPrefs.isEnabled(this)
        switchAutoStart.setOnCheckedChangeListener { _, checked ->
            AutoStartPrefs.setEnabled(this, checked)
            if (checked) {
                slotUis.forEach { capture?.ensureHttpRunning(it.index) }
                refreshHttpUi(forceList = true)
            }
        }

        if (!launchedViaUsbAttach) {
            UsbAutoLaunch.syncFromPrefs(this)
        }
        switchAutoLaunch.isChecked = UsbAutoLaunch.isEnabled(this)
        switchAutoLaunch.setOnCheckedChangeListener { _, checked ->
            // Quest: never disable the alias that launched this task; prefs still update.
            UsbAutoLaunch.setEnabled(
                this,
                checked,
                applyComponent = !launchedViaUsbAttach || checked,
            )
        }

        switchBattery.setOnCheckedChangeListener { _, checked ->
            BatteryKeepAlive.setWanted(this, checked)
            if (checked && !BatteryKeepAlive.isExempt(this)) {
                BatteryKeepAlive.openExemptionUi(this)
            }
            fpsHandler.post { syncBatterySwitch() }
        }

        bindFaceTrackUi()

        handleUsbAttachIntent(intent)
        fpsHandler.post(fpsRunnable)
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bindCaptureService()
    }

    override fun onResume() {
        super.onResume()
        uiResumed = true
        syncBatterySwitch()
        slotUis.forEach { ui ->
            if (ui.switchPreview.isChecked) {
                ui.previewController.setEnabled(true)
                ui.previewController.syncNative()
            }
        }
    }

    override fun onPause() {
        uiResumed = false
        slotUis.forEach { it.previewController.setEnabled(false) }
        // Quest: do not apply a "disabled" pref onto the alias that launched this task.
        if (!launchedViaUsbAttach) {
            UsbAutoLaunch.syncFromPrefs(this)
        }
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachIntent(intent)
    }

    override fun onCaptureStateChanged() {
        runOnUiThread {
            renderCaptureState()
            maybePromptKeepAlive()
        }
    }

    // ------------------------------------------------------------- view binding

    private fun bindSlot(ui: SlotUi) {
        val cardId = if (ui.index == 0) R.id.cardCamera1 else R.id.cardCamera2
        val card = findViewById<View>(cardId)
        ui.title = card.findViewById(R.id.tvCameraTitle)
        ui.tvStatus = card.findViewById(R.id.tvStatus)
        ui.tvFps = card.findViewById(R.id.tvFps)
        ui.dropdownDevice = card.findViewById(R.id.dropdownDevice)
        ui.layoutResolution = card.findViewById(R.id.layoutResolution)
        ui.dropdownResolution = card.findViewById(R.id.dropdownResolution)
        ui.layoutFps = card.findViewById(R.id.layoutFps)
        ui.dropdownFps = card.findViewById(R.id.dropdownFps)
        ui.switchStream = card.findViewById(R.id.switchStream)
        ui.switchPreview = card.findViewById(R.id.switchPreview)
        ui.previewContainer = card.findViewById(R.id.previewContainer)
        ui.imagePreview = card.findViewById(R.id.imagePreview)
        ui.title.text = getString(R.string.camera_title, ui.index + 1)

        val streamRootId = if (ui.index == 0) R.id.streamBlock1 else R.id.streamBlock2
        val streamRoot = findViewById<View>(streamRootId)
        ui.streamTitle = streamRoot.findViewById(R.id.tvStreamTitle)
        ui.editHttpPort = streamRoot.findViewById(R.id.editHttpPort)
        ui.btnApplyHttpPort = streamRoot.findViewById(R.id.btnApplyHttpPort)
        ui.tvHttpState = streamRoot.findViewById(R.id.tvHttpState)
        ui.listAccessUrls = streamRoot.findViewById(R.id.listAccessUrls)
        ui.tvHttpNoUrls = streamRoot.findViewById(R.id.tvHttpNoUrls)
        ui.streamTitle.text = getString(R.string.stream_block_title, ui.index + 1)

        ui.previewController = FramePreviewController(ui.imagePreview, ui.index)
        setDropdownEnabled(ui.layoutResolution, ui.dropdownResolution, enabled = false)
        setDropdownEnabled(ui.layoutFps, ui.dropdownFps, enabled = false)

        ui.switchPreview.isChecked = false
        ui.previewContainer.visibility = View.GONE
        ui.switchPreview.setOnCheckedChangeListener { _, checked ->
            FileLogger.log("[${ui.index}] Preview switch: $checked")
            ui.previewController.setEnabled(checked)
            ui.previewContainer.visibility = if (checked) View.VISIBLE else View.GONE
        }
        ui.switchStream.setOnCheckedChangeListener { _, isChecked ->
            onStreamSwitchChanged(ui.index, isChecked)
        }
        ui.btnApplyHttpPort.setOnClickListener { applyHttpPortFromUi(ui.index) }
        ui.editHttpPort.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyHttpPortFromUi(ui.index)
                true
            } else {
                false
            }
        }
        ui.dropdownDevice.setOnItemClickListener { _, _, pos, _ ->
            if (ui.suppressDeviceCallback) return@setOnItemClickListener
            val service = capture ?: return@setOnItemClickListener
            val device = service.uvcDevices.getOrNull(pos) ?: return@setOnItemClickListener
            ui.selectedDeviceName = device.deviceName
            service.selectDevice(ui.index, device)
        }
        ui.dropdownResolution.setOnItemClickListener { _, _, pos, _ ->
            if (ui.suppressResolutionCallback) return@setOnItemClickListener
            val mode = capture?.snapshot(ui.index)?.modes?.getOrNull(pos)
                ?: return@setOnItemClickListener
            capture?.setResolution(ui.index, mode)
        }
        ui.dropdownFps.setOnItemClickListener { _, _, _, _ ->
            if (ui.suppressFpsCallback) return@setOnItemClickListener
            val fps = StreamMode.parseFpsLabel(ui.dropdownFps.text?.toString().orEmpty())
                ?: return@setOnItemClickListener
            capture?.setFps(ui.index, fps)
        }
    }

    private fun bindCaptureService() {
        val intent = Intent(this, CaptureService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    // ------------------------------------------------------- 面捕眼追软件直连 UI

    private fun bindFaceTrackUi() {
        switchFaceTrack = findViewById(R.id.switchFaceTrack)
        tvFaceTrackStatus = findViewById(R.id.tvFaceTrackStatus)
        dropdownFaceTrackFlag1 = findViewById(R.id.dropdownFaceTrackFlag1)
        dropdownFaceTrackFlag2 = findViewById(R.id.dropdownFaceTrackFlag2)

        bindFaceTrackPosition(0, dropdownFaceTrackFlag1)
        bindFaceTrackPosition(1, dropdownFaceTrackFlag2)

        switchFaceTrack.setOnCheckedChangeListener { _, checked ->
            capture?.setFaceTrackEnabled(checked)
            renderFaceTrack()
        }
    }

    private fun bindFaceTrackPosition(index: Int, dropdown: AutoCompleteTextView) {
        dropdown.setAdapter(
            ArrayAdapter(this, R.layout.item_spinner_dropdown, FaceTrackProtocol.positionLabels)
        )
        // Position can be picked at any time; it only stamps the UDP frames while
        // 面捕眼追软件直连 is on, so the field stays interactive even when the switch
        // is off. Never re-configures the widget from the periodic render loop.
        dropdown.setOnClickListener {
            if (dropdown.isEnabled) dropdown.showDropDown()
        }
        dropdown.setOnItemClickListener { _, _, pos, _ ->
            if (suppressFaceTrackFlagCallback) return@setOnItemClickListener
            val flag = FaceTrackProtocol.positionValues.getOrElse(pos) { FaceTrackProtocol.FLAG_OFF }
            capture?.setFaceTrackFlag(index, flag)
            renderFaceTrack()
        }
    }

    private fun renderFaceTrack() {
        val service = capture ?: return
        if (!::switchFaceTrack.isInitialized) return
        val state = service.faceTrackState()
        if (switchFaceTrack.isChecked != state.enabled) {
            switchFaceTrack.setOnCheckedChangeListener(null)
            switchFaceTrack.isChecked = state.enabled
            switchFaceTrack.setOnCheckedChangeListener { _, checked ->
                capture?.setFaceTrackEnabled(checked)
                renderFaceTrack()
            }
        }
        tvFaceTrackStatus.text = when {
            !state.enabled -> getString(R.string.facetrack_status_off)
            state.connected -> getString(
                R.string.facetrack_status_connected,
                state.serverIp ?: "?",
                state.fps,
            )
            else -> getString(R.string.facetrack_status_waiting)
        }
        syncFaceTrackFlagText(0, dropdownFaceTrackFlag1, state.flags.getOrElse(0) { FaceTrackProtocol.FLAG_OFF })
        syncFaceTrackFlagText(1, dropdownFaceTrackFlag2, state.flags.getOrElse(1) { FaceTrackProtocol.FLAG_OFF })
    }

    /**
     * Only touch the dropdown when the flag really changed — a periodic
     * re-setText/setEnabled here would dismiss an open dropdown list.
     */
    private fun syncFaceTrackFlagText(index: Int, dropdown: AutoCompleteTextView, flag: Int) {
        if (faceTrackFlagShown[index] == flag) return
        faceTrackFlagShown[index] = flag
        suppressFaceTrackFlagCallback = true
        try {
            dropdown.setText(FaceTrackProtocol.labelFor(flag), false)
        } finally {
            suppressFaceTrackFlagCallback = false
        }
    }

    // ---------------------------------------------------------------- keep-alive

    private fun maybePromptKeepAlive() {
        if (keepAlivePromptedThisSession) return
        val service = capture ?: return
        val waitingCamera = AppPermissions.missingCameraAccess(this) &&
            service.waitingCameraPermission
        val wantsKeepAlive = AutoStartPrefs.isEnabled(this) ||
            service.anyStreaming ||
            slotUis.any { CaptureKeepAlive.wantStreaming(this, it.index) } ||
            intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
            waitingCamera
        if (!wantsKeepAlive) return
        if (AppPermissions.missing(this).isEmpty() &&
            (BatteryKeepAlive.isExempt(this) ||
                batterySkippedThisSession ||
                !BatteryKeepAlive.isWanted(this))
        ) {
            return
        }
        keepAlivePromptedThisSession = true
        ensureKeepAliveThen {
            capture?.onRuntimePermissionsReady()
        }
    }

    private fun ensureKeepAliveThen(after: () -> Unit) {
        val missing = AppPermissions.missing(this)
        val needBattery = BatteryKeepAlive.isWanted(this) &&
            !BatteryKeepAlive.isExempt(this) &&
            !batterySkippedThisSession
        if (missing.isEmpty() && !needBattery) {
            after()
            return
        }
        pendingAfterKeepAlive = after
        showKeepAliveRationale(missing, needBattery)
    }

    private fun showKeepAliveRationale(missing: Array<String>, needBattery: Boolean) {
        if (permissionDialogShowing) return
        permissionDialogShowing = true
        val goSettings = missing.isNotEmpty() && AppPermissions.anyPermanentlyDenied(this, missing)
        val title = if (goSettings) R.string.perm_dialog_denied_title else R.string.perm_dialog_title
        val message = if (goSettings) {
            getString(R.string.perm_dialog_denied_body)
        } else {
            PermissionRationale.message(this, missing.toList(), needBattery)
        }
        val positive = if (goSettings) R.string.perm_dialog_settings else R.string.perm_dialog_allow
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ ->
                permissionDialogShowing = false
                if (goSettings) {
                    AppPermissions.openAppSettings(this)
                    if (needBattery) requestBatteryExemption() else finishKeepAliveChain()
                } else if (missing.isNotEmpty()) {
                    FileLogger.log("Requesting permissions after rationale: ${missing.toList()}")
                    AppPermissions.markAskedSystemPrompt(this, missing)
                    pendingBatteryAfterPerms = needBattery
                    runtimePermissionLauncher.launch(missing)
                } else {
                    requestBatteryExemption()
                }
            }
            .setNegativeButton(R.string.perm_dialog_later) { _, _ ->
                permissionDialogShowing = false
                if (needBattery) batterySkippedThisSession = true
                FileLogger.log("Keep-alive prompts skipped by user")
                finishKeepAliveChain()
            }
            .setOnCancelListener {
                permissionDialogShowing = false
                if (needBattery) batterySkippedThisSession = true
                finishKeepAliveChain()
            }
            .show()
    }

    private fun continueKeepAliveAfterPermissions() {
        val needBattery = pendingBatteryAfterPerms &&
            BatteryKeepAlive.isWanted(this) &&
            !BatteryKeepAlive.isExempt(this) &&
            !batterySkippedThisSession
        pendingBatteryAfterPerms = false
        if (needBattery) {
            requestBatteryExemption()
        } else {
            finishKeepAliveChain()
        }
    }

    private fun requestBatteryExemption() {
        try {
            batteryExemptionLauncher.launch(BatteryKeepAlive.requestExemptionIntent(this))
        } catch (t: Throwable) {
            FileLogger.log("Battery exemption launcher: ${t.message}")
            BatteryKeepAlive.openExemptionUi(this)
            finishKeepAliveChain()
        }
    }

    private fun finishKeepAliveChain() {
        val next = pendingAfterKeepAlive
        pendingAfterKeepAlive = null
        pendingBatteryAfterPerms = false
        next?.invoke()
    }

    private fun handleUsbAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = readUsbDeviceExtra(intent) ?: return
        FileLogger.log("USB attach intent: ${device.deviceName} ${device.productName}")
        val service = capture
        if (service == null) {
            pendingAttachDevice = device
            return
        }
        service.onUsbAttachIntent(device)
    }

    private fun readUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun renderCaptureState() {
        slotUis.forEach { renderSlot(it) }
        renderFaceTrack()
    }

    private fun renderSlot(ui: SlotUi) {
        val service = capture ?: return
        val snapshot = service.snapshot(ui.index)
        ui.tvStatus.text = snapshot.status
        syncStreamSwitch(ui, snapshot.streaming)
        bindDeviceDropdown(
            ui,
            service.uvcDevices,
            snapshot.device ?: service.uvcDevices.firstOrNull(),
        )
        bindModeDropdowns(ui, snapshot.modes, snapshot.selectedMode, snapshot.selectedFps)
        if (!ui.editHttpPort.isFocused) {
            val shown = ui.editHttpPort.text?.toString().orEmpty()
            if (shown != snapshot.httpPort.toString()) {
                ui.editHttpPort.setText(snapshot.httpPort.toString())
            }
        }
        renderHttpState(ui, snapshot, forceList = false)
        if (!snapshot.deviceOpen) {
            ui.previewController.clearFrame()
        } else if (snapshot.streaming && ui.switchPreview.isChecked && uiResumed) {
            ui.previewController.syncNative()
        }
    }

    private fun bindDeviceDropdown(ui: SlotUi, devices: List<UsbDevice>, selected: UsbDevice?) {
        val sig = devices.joinToString("|") { it.deviceName } + "#" + (selected?.deviceName ?: "")
        if (sig == ui.lastDeviceSig) return
        ui.lastDeviceSig = sig
        val labels = devices.map { UvcDeviceFinder.labelFor(it) }
        val selectedIndex = selected?.let { sel ->
            devices.indexOfFirst { it.deviceName == sel.deviceName }
        }?.takeIf { it >= 0 } ?: 0
        ui.selectedDeviceName = devices.getOrNull(selectedIndex)?.deviceName
        ui.suppressDeviceCallback = true
        ui.dropdownDevice.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, labels))
        if (labels.isNotEmpty()) {
            ui.dropdownDevice.setText(labels[selectedIndex.coerceAtMost(labels.lastIndex)], false)
        } else {
            ui.dropdownDevice.setText("", false)
        }
        ui.suppressDeviceCallback = false
    }

    private fun bindModeDropdowns(
        ui: SlotUi,
        modes: List<StreamMode>,
        selected: StreamMode?,
        fps: Int,
    ) {
        val sig = modes.joinToString("|") { it.sizeLabel } + "#" + (selected?.sizeLabel ?: "") + "@$fps"
        if (sig == ui.lastModeSig) return
        ui.lastModeSig = sig
        val labels = modes.map { it.sizeLabel }
        ui.suppressResolutionCallback = true
        ui.dropdownResolution.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, labels))
        if (selected != null && modes.isNotEmpty()) {
            ui.dropdownResolution.setText(selected.sizeLabel, false)
            setDropdownEnabled(ui.layoutResolution, ui.dropdownResolution, modes.size > 1)
            bindFpsDropdown(ui, selected, fps)
        } else {
            ui.dropdownResolution.setText("", false)
            setDropdownEnabled(ui.layoutResolution, ui.dropdownResolution, enabled = false)
            clearFpsDropdown(ui)
        }
        ui.suppressResolutionCallback = false
    }

    private fun bindFpsDropdown(ui: SlotUi, mode: StreamMode, preferFps: Int) {
        val labels = mode.fpsLabels()
        val selectedFps = mode.nearestFps(preferFps)
        ui.suppressFpsCallback = true
        ui.dropdownFps.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, labels))
        ui.dropdownFps.setText("$selectedFps fps", false)
        setDropdownEnabled(ui.layoutFps, ui.dropdownFps, labels.size > 1)
        ui.suppressFpsCallback = false
    }

    private fun clearFpsDropdown(ui: SlotUi) {
        ui.suppressFpsCallback = true
        ui.dropdownFps.setAdapter(ArrayAdapter(this, R.layout.item_spinner_dropdown, emptyList<String>()))
        ui.dropdownFps.setText("", false)
        setDropdownEnabled(ui.layoutFps, ui.dropdownFps, enabled = false)
        ui.suppressFpsCallback = false
    }

    private fun setDropdownEnabled(
        layout: TextInputLayout,
        dropdown: AutoCompleteTextView,
        enabled: Boolean,
    ) {
        layout.isEnabled = enabled
        dropdown.isEnabled = enabled
        dropdown.isClickable = enabled
        layout.endIconMode = if (enabled) {
            TextInputLayout.END_ICON_DROPDOWN_MENU
        } else {
            TextInputLayout.END_ICON_NONE
        }
    }

    private fun applyHttpPortFromUi(index: Int) {
        val service = capture ?: return
        val ui = slotUis[index]
        val raw = ui.editHttpPort.text?.toString()?.trim().orEmpty()
        val p = raw.toIntOrNull()
        if (p == null || p !in 1..65535) {
            updateStatus(ui, getString(R.string.status_bad_port))
            ui.editHttpPort.setText(service.snapshot(index).httpPort.toString())
            refreshHttpUi()
            return
        }
        val ok = service.applyHttpPort(index, p)
        ui.editHttpPort.setText(service.snapshot(index).httpPort.toString())
        updateStatus(
            ui,
            if (ok) getString(R.string.status_http_ok, p) else getString(R.string.status_http_fail, p)
        )
        ui.lastAccessSignature = ""
        ui.lastHttpStateText = ""
        refreshHttpUi(forceList = true)
    }

    private fun refreshHttpUi(forceList: Boolean = false) {
        runOnUiThread {
            val service = capture ?: return@runOnUiThread
            slotUis.forEach { ui ->
                renderHttpState(ui, service.snapshot(ui.index), forceList)
            }
        }
    }

    private fun renderHttpState(
        ui: SlotUi,
        snapshot: CaptureService.SlotSnapshot,
        forceList: Boolean,
    ) {
        val state = if (snapshot.httpRunning) {
            getString(R.string.http_running, snapshot.httpPort, snapshot.httpClients)
        } else {
            getString(R.string.http_stopped, snapshot.httpPort)
        }
        if (state != ui.lastHttpStateText) {
            ui.lastHttpStateText = state
            ui.tvHttpState.text = state
        }
        if (forceList) {
            ui.lastAccessSignature = ""
        }
        renderAccessList(ui, snapshot.endpoints)
    }

    private fun renderAccessList(ui: SlotUi, endpoints: List<HttpStreamController.AccessEndpoint>) {
        val signature = endpoints.joinToString("|") { "${it.tier}:${it.url}" }
        if (signature == ui.lastAccessSignature && ui.listAccessUrls.childCount > 0) {
            ui.tvHttpNoUrls.visibility = if (endpoints.isEmpty()) View.VISIBLE else View.GONE
            return
        }
        ui.lastAccessSignature = signature
        ui.listAccessUrls.removeAllViews()

        if (endpoints.isEmpty()) {
            ui.tvHttpNoUrls.visibility = View.VISIBLE
            return
        }
        ui.tvHttpNoUrls.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        var lastTier: NetworkAddresses.AccessTier? = null
        for (ep in endpoints) {
            if (ep.tier != lastTier) {
                lastTier = ep.tier
                val header = inflater.inflate(R.layout.item_access_group, ui.listAccessUrls, false) as TextView
                header.text = when (ep.tier) {
                    NetworkAddresses.AccessTier.LAN -> getString(R.string.http_group_lan)
                    NetworkAddresses.AccessTier.OTHER -> getString(R.string.http_group_other)
                    NetworkAddresses.AccessTier.LOCAL -> getString(R.string.http_group_local)
                }
                header.setTextColor(
                    ContextCompat.getColor(
                        this,
                        when (ep.tier) {
                            NetworkAddresses.AccessTier.LAN -> R.color.signal
                            NetworkAddresses.AccessTier.OTHER -> R.color.text_secondary
                            NetworkAddresses.AccessTier.LOCAL -> R.color.text_muted
                        }
                    )
                )
                ui.listAccessUrls.addView(header)
            }

            val row = inflater.inflate(R.layout.item_access_url, ui.listAccessUrls, false)
            val badge = row.findViewById<TextView>(R.id.tvAccessBadge)
            val label = row.findViewById<TextView>(R.id.tvAccessLabel)
            val urlView = row.findViewById<TextView>(R.id.tvAccessUrl)
            val hint = row.findViewById<TextView>(R.id.tvAccessHint)

            label.text = "${ep.label} · ${ep.host}"
            urlView.text = ep.url

            when (ep.tier) {
                NetworkAddresses.AccessTier.LAN -> {
                    row.setBackgroundResource(R.drawable.bg_access_row_lan)
                    badge.visibility = View.VISIBLE
                    badge.text = getString(R.string.http_badge_recommend)
                    urlView.setTextColor(ContextCompat.getColor(this, R.color.signal))
                    hint.text = getString(R.string.http_hint_lan)
                    hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
                NetworkAddresses.AccessTier.OTHER -> {
                    row.setBackgroundResource(R.drawable.bg_access_row_other)
                    badge.visibility = View.GONE
                    urlView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                    hint.text = getString(R.string.http_hint_other)
                    hint.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                }
                NetworkAddresses.AccessTier.LOCAL -> {
                    row.setBackgroundResource(R.drawable.bg_access_row_local)
                    badge.visibility = View.GONE
                    urlView.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                    hint.text = getString(R.string.http_hint_local)
                    hint.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                }
            }

            row.setOnClickListener { copyUrl(ep.url) }
            ui.listAccessUrls.addView(row)
        }
    }

    private fun copyUrl(url: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("stream", url))
        Toast.makeText(this, R.string.http_copied, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ actions

    private fun onStreamSwitchChanged(index: Int, isChecked: Boolean) {
        FileLogger.log("[$index] Switch changed: $isChecked")
        if (isChecked) {
            ensureKeepAliveThen { startCaptureFromUi(index) }
        } else {
            capture?.stopCapture(index, userInitiated = true)
        }
    }

    private fun startCaptureFromUi(index: Int) {
        val service = capture ?: return
        val ui = slotUis[index]
        // Resolve by deviceName first: two identical cameras share the same
        // product name, so label matching would always pick the first one.
        val byName = ui.selectedDeviceName?.let { name ->
            service.uvcDevices.firstOrNull { it.deviceName == name }
        }
        val device = byName ?: run {
            val label = ui.dropdownDevice.text?.toString()
            service.uvcDevices.firstOrNull { UvcDeviceFinder.labelFor(it) == label }
        }
        service.startCapture(index, device)
    }

    private fun syncStreamSwitch(ui: SlotUi, checked: Boolean) {
        if (ui.switchStream.isChecked == checked) return
        ui.switchStream.setOnCheckedChangeListener(null)
        ui.switchStream.isChecked = checked
        ui.switchStream.setOnCheckedChangeListener { _, isChecked ->
            onStreamSwitchChanged(ui.index, isChecked)
        }
    }

    private fun syncBatterySwitch() {
        val on = BatteryKeepAlive.isWanted(this) && BatteryKeepAlive.isExempt(this)
        if (switchBattery.isChecked == on) return
        switchBattery.setOnCheckedChangeListener(null)
        switchBattery.isChecked = on
        switchBattery.setOnCheckedChangeListener { _, checked ->
            BatteryKeepAlive.setWanted(this, checked)
            if (checked && !BatteryKeepAlive.isExempt(this)) {
                BatteryKeepAlive.openExemptionUi(this)
            }
            fpsHandler.post { syncBatterySwitch() }
        }
    }

    private fun updateStatus(ui: SlotUi, status: String) {
        runOnUiThread { ui.tvStatus.text = status }
    }

    private fun updateFpsText(ui: SlotUi, fps: Int) {
        runOnUiThread {
            ui.tvFps.visibility = View.VISIBLE
            ui.tvFps.text = getString(R.string.status_fps, fps)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fpsHandler.removeCallbacks(fpsRunnable)
        // Every slot was bound in onCreate, so the controllers are initialized here.
        slotUis.forEach { ui -> ui.previewController.release() }
        if (bound) {
            capture?.removeListener(this)
            runCatching { unbindService(connection) }
            bound = false
        }
        capture = null
    }
}
