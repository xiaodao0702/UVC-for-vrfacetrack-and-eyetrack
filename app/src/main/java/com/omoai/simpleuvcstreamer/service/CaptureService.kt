package com.omoai.simpleuvcstreamer.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.omoai.simpleuvcstreamer.R
import com.omoai.simpleuvcstreamer.cymple.CympleBridge
import com.omoai.simpleuvcstreamer.cymple.CymplePrefs
import com.omoai.simpleuvcstreamer.stream.HttpStreamController
import com.omoai.simpleuvcstreamer.usb.UsbDeviceMonitor
import com.omoai.simpleuvcstreamer.usb.UvcDeviceFinder
import com.omoai.simpleuvcstreamer.util.AppPermissions
import com.omoai.simpleuvcstreamer.util.FileLogger
import com.omoai.simpleuvcstreamer.uvc.AutoStartPrefs
import com.omoai.simpleuvcstreamer.uvc.DeviceHistory
import com.omoai.simpleuvcstreamer.uvc.StreamMode
import com.omoai.simpleuvcstreamer.uvc.UvcNative
import com.omoai.simpleuvcstreamer.uvc.UvcSession
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns USB capture + HTTP MJPEG for up to [UvcNative.CAMERA_COUNT] cameras at once.
 *
 * Every camera slot (0 / 1) has its own UVC device handle, its own HTTP server on
 * its own port, and its own lifecycle state. Promoted to a connectedDevice
 * foreground service while the user wants capture, so leaving the UI does not
 * tear down the streams.
 */
class CaptureService : Service(), UsbDeviceMonitor.Listener {

    fun interface Listener {
        fun onCaptureStateChanged()
    }

    inner class LocalBinder : Binder() {
        fun service(): CaptureService = this@CaptureService
    }

    /** Immutable snapshot of one camera slot, read by the UI. */
    data class SlotSnapshot(
        val index: Int,
        val device: UsbDevice?,
        val deviceOpen: Boolean,
        val streaming: Boolean,
        val modes: List<StreamMode>,
        val selectedMode: StreamMode?,
        val selectedFps: Int,
        val status: String,
        val httpRunning: Boolean,
        val httpPort: Int,
        val httpClients: Int,
        val endpoints: List<HttpStreamController.AccessEndpoint>,
    )

    /** Mutable per-camera state. */
    private class Slot(val index: Int) {
        lateinit var session: UvcSession
        lateinit var http: HttpStreamController

        var modes: List<StreamMode> = emptyList()
        var selectedMode: StreamMode? = null
        var selectedFps: Int = 0
        var statusText: String = ""

        /** Capture was on when the cable came out — resume after the same camera returns. */
        var resumeOnReattach: Boolean = false

        var permissionWaitName: String? = null
        var pendingStartAfterPermission: Boolean = false
        var permissionRetryDevice: UsbDevice? = null
        var permissionRetryStart: Boolean = false
        var permissionRetryRunnable: Runnable? = null

        /** Hot-unplug settle window; blocks auto reopen for this slot only. */
        var suppressAutoOpenUntilMs: Long = 0L

        val isStreaming: Boolean get() = session.isStreaming
        val device: UsbDevice? get() = session.currentDevice
        val isDeviceOpen: Boolean get() = session.isDeviceOpen
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var locks: KeepAliveLocks

    lateinit var usbManager: UsbManager
        private set
    private lateinit var usbMonitor: UsbDeviceMonitor

    /** Cymple 直连（DIY 摄像头 UDP 协议）发送器。 */
    lateinit var cymple: CympleBridge
        private set

    private val slots: Array<Slot> = Array(UvcNative.CAMERA_COUNT) { Slot(it) }

    var uvcDevices: List<UsbDevice> = emptyList()
        private set
    var nativeReady: Boolean = false
        private set

    private var bindCount = 0
    private var foreground = false
    private var pendingNotifyText: String? = null

    private val usbRetryRunnable = Runnable { refreshDeviceList() }

    /** Number of camera slots this service drives. */
    val slotCount: Int get() = slots.size

    val anyStreaming: Boolean get() = slots.any { it.isStreaming }

    /** True while any slot is blocked on the camera permission (e.g. Quest). */
    val waitingCameraPermission: Boolean
        get() = slots.any { it.statusText == getString(R.string.status_waiting_camera_permission) }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onCaptureStateChanged()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.log("CaptureService onCreate")
        locks = KeepAliveLocks(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        slots.forEach { slot ->
            slot.session = UvcSession(usbManager, slot.index)
            slot.http = HttpStreamController(this, slot.index)
            slot.statusText = getString(R.string.status_ready)
        }
        cymple = CympleBridge(this) { handler.post { notifyUi() } }

        if (!UvcNative.isLibLoaded) {
            slots.forEach { it.statusText = getString(R.string.status_lib_missing) }
            FileLogger.log("FATAL: Library not loaded")
            notifyUi()
            return
        }
        try {
            val res = UvcNative.nativeInit()
            FileLogger.log("nativeInit result: $res")
            nativeReady = res == 0
            if (!nativeReady) {
                slots.forEach { it.statusText = getString(R.string.status_init_crash) }
            }
        } catch (t: Throwable) {
            FileLogger.log("nativeInit CRASHED: ${t.message}")
            nativeReady = false
            slots.forEach { it.statusText = getString(R.string.status_init_crash) }
            notifyUi()
            return
        }

        usbMonitor = UsbDeviceMonitor(this, ACTION_USB_PERMISSION, this)
        usbMonitor.register()

        if (CymplePrefs.isEnabled(this)) {
            cymple.setEnabled(true)
        }

        if (AutoStartPrefs.isEnabled(this) || anySlotWantsStreaming()) {
            requestKeepAlive(getString(R.string.notify_waiting))
            slots.forEach { it.http.ensureStarted() }
        }
        refreshDeviceList()
    }

    override fun onBind(intent: Intent?): IBinder {
        bindCount++
        FileLogger.log("CaptureService onBind count=$bindCount")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        bindCount = (bindCount - 1).coerceAtLeast(0)
        FileLogger.log("CaptureService onUnbind count=$bindCount streaming=$anyStreaming")
        if (!shouldStayAlive()) {
            stopSelf()
        }
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FileLogger.log("CaptureService onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                slots.forEach { stopCapture(it.index, userInitiated = true) }
                return if (shouldStayAlive()) START_STICKY else START_NOT_STICKY
            }
        }
        val text = pendingNotifyText
            ?: slots.firstOrNull { it.isStreaming }?.statusText
            ?: getString(R.string.notify_waiting)
        startForegroundInternal(text)
        if (!shouldStayAlive()) {
            leaveForeground()
            if (bindCount == 0) stopSelf()
            return START_NOT_STICKY
        }
        if (slots.none { it.isStreaming } && nativeReady) {
            refreshDeviceList()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        FileLogger.log("CaptureService onTaskRemoved streaming=$anyStreaming")
        if (!shouldStayAlive()) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        FileLogger.log("CaptureService onDestroy")
        handler.removeCallbacks(usbRetryRunnable)
        slots.forEach { slot ->
            slot.permissionRetryRunnable?.let { handler.removeCallbacks(it) }
            slot.permissionRetryRunnable = null
        }
        if (::usbMonitor.isInitialized) {
            usbMonitor.unregister()
        }
        if (::locks.isInitialized) {
            locks.release()
        }
        leaveForeground()
        if (::usbManager.isInitialized) {
            slots.forEach { slot ->
                runCatching { slot.session.close() }
                    .onFailure { FileLogger.log("[${slot.index}] session.close on destroy: ${it.message}") }
                runCatching { slot.http.stop() }
            }
        }
        if (::cymple.isInitialized) {
            cymple.setEnabled(false)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI reads

    fun snapshot(index: Int): SlotSnapshot {
        val slot = slots[index]
        val running = slot.http.isRunning
        val port = if (running) UvcNative.nativeGetHttpServerPort(index) else slot.http.port
        return SlotSnapshot(
            index = index,
            device = slot.device,
            deviceOpen = slot.isDeviceOpen,
            streaming = slot.isStreaming,
            modes = slot.modes,
            selectedMode = slot.selectedMode,
            selectedFps = slot.selectedFps,
            status = slot.statusText,
            httpRunning = running,
            httpPort = port,
            httpClients = slot.http.clientCount,
            endpoints = slot.http.accessEndpoints(),
        )
    }

    fun frameCountPerSecond(index: Int): Int = slots[index].session.frameCountPerSecond()

    // ------------------------------------------------------------- UI commands

    fun onUsbAttachIntent(device: UsbDevice) {
        if (!nativeReady) return
        FileLogger.log("USB attach intent: ${device.deviceName} ${device.productName}")
        if (slots.all { it.isStreaming }) {
            FileLogger.log("USB attach intent while all cameras streaming — ignore chooser relaunch")
            return
        }
        refreshDeviceList(preferDevice = device)
    }

    /** Called after the UI finishes the runtime-permission / battery keep-alive prompts. */
    fun onRuntimePermissionsReady() {
        if (!nativeReady) return
        refreshDeviceList()
    }

    fun selectDevice(index: Int, device: UsbDevice) {
        val slot = slots[index]
        val shouldStart = slot.isStreaming ||
            CaptureKeepAlive.wantStreaming(this, index) ||
            slot.resumeOnReattach
        prepareDevice(slot, device, startStream = shouldStart)
    }

    fun setResolution(index: Int, mode: StreamMode) {
        val slot = slots[index]
        slot.selectedMode = mode
        slot.selectedFps = mode.nearestFps(slot.selectedFps.takeIf { it > 0 } ?: mode.defaultFps)
        notifyUi()
        if (slot.isStreaming) applySelectedMode(slot)
    }

    fun setFps(index: Int, fps: Int) {
        val slot = slots[index]
        val mode = slot.selectedMode ?: return
        slot.selectedFps = mode.nearestFps(fps)
        notifyUi()
        if (slot.isStreaming) applySelectedMode(slot)
    }

    fun startCapture(index: Int, prefer: UsbDevice? = null) {
        val slot = slots[index]
        CaptureKeepAlive.setWantStreaming(this, index, true)
        if (uvcDevices.isEmpty()) {
            FileLogger.log("[$index] startCapture: No UVC devices")
            CaptureKeepAlive.setWantStreaming(this, index, false)
            slot.statusText = getString(R.string.status_no_device)
            notifyUi()
            return
        }
        // The UI may pass a camera that is already open in the other slot (this
        // used to happen whenever both cameras share the same product name and
        // the selection was resolved by label). Never hand the same device to
        // two slots — fall back to this slot's own camera, then to a free one.
        val usedByOther = devicesUsedByOtherSlots(slot)
        val preferred = prefer?.takeIf { it.deviceName !in usedByOther }
        if (prefer != null && preferred == null) {
            FileLogger.log(
                "[$index] requested camera ${prefer.deviceName} is in use by the other slot — using a free camera"
            )
        }
        val device = preferred
            ?: slot.device?.takeIf { d -> uvcDevices.any { it.deviceName == d.deviceName } }
            ?: pickDeviceFor(slot)
        if (device == null) {
            FileLogger.log("[$index] startCapture: no free camera")
            slot.statusText = getString(R.string.status_no_free_camera)
            notifyUi()
            return
        }
        requestKeepAlive(getString(R.string.notify_waiting))
        ensureHttpRunning(slot)
        prepareDevice(slot, device, startStream = true)
    }

    fun stopCapture(index: Int, userInitiated: Boolean) {
        val slot = slots[index]
        if (userInitiated) {
            CaptureKeepAlive.setWantStreaming(this, index, false)
            slot.resumeOnReattach = false
        }
        slot.session.stopStream()
        slot.statusText = getString(R.string.status_idle)
        syncCympleSlot(slot)
        notifyUi()
    }

    fun applyHttpPort(index: Int, port: Int): Boolean {
        val ok = slots[index].http.applyPort(port)
        notifyUi()
        return ok
    }

    fun ensureHttpRunning(index: Int) {
        ensureHttpRunning(slots[index])
    }

    // ------------------------------------------------------------- Cymple 直连

    fun cympleState(): CympleBridge.State = cymple.snapshot()

    fun setCympleEnabled(enabled: Boolean) {
        if (!::cymple.isInitialized) return
        CymplePrefs.setEnabled(this, enabled)
        if (enabled && !UvcNative.isLibLoaded) {
            FileLogger.log("Cymple: native lib missing — cannot send")
            notifyUi()
            return
        }
        cymple.setEnabled(enabled)
        slots.forEach { syncCympleSlot(it) }
        notifyUi()
    }

    fun setCympleFlag(index: Int, flag: Int) {
        if (index !in slots.indices || !::cymple.isInitialized) return
        CymplePrefs.setFlag(this, index, flag)
        cymple.setFlag(index, flag)
        syncCympleSlot(slots[index])
        notifyUi()
    }

    /** Native Cymple frame slot + UDP sender follow the slot's streaming state. */
    private fun syncCympleSlot(slot: Slot) {
        if (!::cymple.isInitialized) return
        val shouldSend = slot.isStreaming &&
            cymple.isEnabled() &&
            CymplePrefs.flagFor(this, slot.index) >= 0
        if (shouldSend) {
            UvcNative.nativeSetCympleEnabled(slot.index, true)
            cymple.startSlot(slot.index)
        } else {
            cymple.stopSlot(slot.index)
            if (UvcNative.isLibLoaded) {
                UvcNative.nativeSetCympleEnabled(slot.index, false)
            }
        }
    }

    // ------------------------------------------------------------ USB listeners

    override fun onDeviceAttached(device: UsbDevice?) {
        FileLogger.log("USB attached: ${device?.deviceName}")
        refreshDeviceList(preferDevice = device)
    }

    override fun onDeviceDetached(device: UsbDevice) {
        val now = System.currentTimeMillis()
        slots.forEach { slot ->
            val waitingPerm = slot.permissionWaitName == device.deviceName ||
                slot.permissionRetryDevice?.deviceName == device.deviceName
            if (waitingPerm) {
                FileLogger.log("[$slot.index] USB detach while waiting permission — cancel stale request")
                slot.permissionWaitName = null
                slot.permissionRetryDevice = null
                slot.pendingStartAfterPermission = false
                slot.permissionRetryRunnable?.let { handler.removeCallbacks(it) }
                slot.permissionRetryRunnable = null
                slot.suppressAutoOpenUntilMs = now + 1500L
            }
        }
        slots.forEach { slot ->
            if (UvcDeviceFinder.sameDevice(device, slot.device)) {
                FileLogger.log("[$slot.index] UVC device detached — stopping cleanly")
                slot.resumeOnReattach = slot.isStreaming || CaptureKeepAlive.wantStreaming(this, slot.index)
                if (::usbManager.isInitialized) {
                    runCatching { slot.session.close() }
                        .onFailure { FileLogger.log("[$slot.index] session.close on detach: ${it.message}") }
                }
                slot.modes = emptyList()
                slot.selectedMode = null
                slot.selectedFps = 0
                slot.pendingStartAfterPermission = false
                slot.permissionWaitName = null
                slot.suppressAutoOpenUntilMs = now + 2500L
                slot.statusText = getString(R.string.status_device_detached)
                syncCympleSlot(slot)
                notifyUi()
            }
        }
        refreshDeviceList()
    }

    override fun onPermissionResult(device: UsbDevice?, granted: Boolean) {
        val name = device?.deviceName
        val target = if (name == null) {
            null
        } else {
            slots.firstOrNull { it.permissionWaitName == name }
                ?: slots.firstOrNull { it.permissionRetryDevice?.deviceName == name }
        }
        if (target == null) {
            FileLogger.log("USB permission result ignored — no slot waiting for ${name ?: "?"}")
            notifyUi()
            return
        }
        target.permissionWaitName = null
        // Pico flapping: permission callback can arrive after DETACH.
        val live = name?.let { usbManager.deviceList[it] }
        if (live == null) {
            FileLogger.log("[${target.index}] USB permission result ignored — device no longer connected")
            target.pendingStartAfterPermission = false
            notifyUi()
            return
        }
        val hasIt = usbManager.hasPermission(live)
        if (granted || hasIt) {
            val shouldStart = target.pendingStartAfterPermission ||
                CaptureKeepAlive.wantStreaming(this, target.index) ||
                target.resumeOnReattach
            target.pendingStartAfterPermission = false
            prepareDevice(target, live, startStream = shouldStart)
        } else {
            target.pendingStartAfterPermission = false
            // Release the reservation so the other slot may ask for this camera.
            target.permissionRetryDevice = null
            target.resumeOnReattach = false
            CaptureKeepAlive.setWantStreaming(this, target.index, false)
            target.statusText = getString(R.string.status_permission_denied)
            notifyUi()
        }
    }

    // ------------------------------------------------------- device management

    /**
     * Enumerate UVC devices and (re)assign one to every idle camera slot.
     * Slots that are already streaming keep their device; hot-unplug closes them
     * earlier in [onDeviceDetached].
     */
    private fun refreshDeviceList(preferDevice: UsbDevice? = null) {
        if (!nativeReady) {
            notifyUi()
            return
        }
        val previous = uvcDevices
        uvcDevices = UvcDeviceFinder.listUvcDevices(usbManager)
        FileLogger.log("UVC Device count: ${uvcDevices.size}")

        for (device in uvcDevices) {
            val isNew = previous.none { it.deviceName == device.deviceName }
            if (isNew) {
                DeviceHistory.touch(this, device)
            }
        }
        preferDevice?.let { preferred ->
            uvcDevices.firstOrNull { it.deviceName == preferred.deviceName }
                ?.let { DeviceHistory.touch(this, it) }
        }

        slots.forEach { slot -> updateSlotDevices(slot, preferDevice) }
        scheduleRefreshAfterSuppression()
    }

    private fun updateSlotDevices(slot: Slot, preferDevice: UsbDevice?) {
        if (slot.isStreaming) return

        val usedByOther = devicesUsedByOtherSlots(slot)
        val candidates = uvcDevices.filter { it.deviceName !in usedByOther }
        val current = slot.device?.let { cur ->
            uvcDevices.firstOrNull { it.deviceName == cur.deviceName }
        }
        val candidate: UsbDevice? = current
            ?: preferDevice?.takeIf { preferred ->
                candidates.any { it.deviceName == preferred.deviceName }
            }
            ?: DeviceHistory.pickPreferred(this, candidates)
            ?: candidates.firstOrNull()

        if (candidate == null) {
            if (slot.isDeviceOpen) {
                FileLogger.log("[$slot.index] no device left — closing slot")
                runCatching { slot.session.close() }
            }
            slot.modes = emptyList()
            slot.selectedMode = null
            slot.selectedFps = 0
            slot.statusText = when {
                uvcDevices.isEmpty() -> {
                    if (slot.resumeOnReattach || CaptureKeepAlive.wantStreaming(this, slot.index)) {
                        getString(R.string.status_device_detached)
                    } else {
                        val rawCount = usbManager.deviceList.size
                        if (rawCount > 0) {
                            getString(R.string.status_no_uvc_but_usb, rawCount)
                        } else {
                            getString(R.string.status_no_device)
                        }
                    }
                }
                slot.resumeOnReattach || CaptureKeepAlive.wantStreaming(this, slot.index) ->
                    getString(R.string.status_no_free_camera)
                else -> getString(R.string.status_no_free_camera)
            }
            notifyUi()
            return
        }

        val suppressed = isAutoOpenSuppressed(slot)
        val wantStart = !suppressed && slotWantsAutoStart(slot)
        if (wantStart) {
            ensureHttpRunning(slot)
        }
        when {
            wantStart -> prepareDevice(slot, candidate, startStream = true)
            // Just unplugged: keep the "disconnected, waiting" state instead of
            // racing a reopen against a device the USB stack is still tearing down.
            suppressed -> notifyUi()
            usbManager.hasPermission(candidate) -> prepareDevice(slot, candidate, startStream = false)
            else -> {
                // Pico: UsbPermissionActivity often hides our virtual display ("crash").
                // Only request USB permission when opening/streaming.
                slot.statusText = getString(
                    R.string.status_uvc_listed,
                    candidate.productName ?: candidate.deviceName,
                )
                notifyUi()
            }
        }
    }

    private fun pickDeviceFor(slot: Slot): UsbDevice? {
        val usedByOther = devicesUsedByOtherSlots(slot)
        val candidates = uvcDevices.filter { it.deviceName !in usedByOther }
        return DeviceHistory.pickPreferred(this, candidates) ?: candidates.firstOrNull()
    }

    /**
     * Devices another slot already owns — open, or being opened while the USB
     * permission dialog is up. Reserving the requested device stops both slots
     * from racing the same camera (which would fail the second claimInterface).
     */
    private fun devicesUsedByOtherSlots(slot: Slot): Set<String> {
        return slots
            .filter { it.index != slot.index }
            .flatMap { other ->
                listOfNotNull(
                    other.device?.deviceName,
                    other.permissionWaitName,
                    other.permissionRetryDevice?.deviceName,
                )
            }
            .toSet()
    }

    private fun prepareDevice(slot: Slot, device: UsbDevice, startStream: Boolean) {
        val index = slot.index
        if (usbManager.deviceList[device.deviceName] == null) {
            FileLogger.log("[$index] prepareDevice skipped — not in deviceList: ${device.deviceName}")
            return
        }
        if (UvcDeviceFinder.sameDevice(device, slot.device) && slot.isDeviceOpen) {
            if (startStream && !slot.isStreaming) {
                applySelectedMode(slot)
            } else {
                notifyUi()
            }
            return
        }

        val other = slots.firstOrNull {
            it.index != index && it.device?.deviceName == device.deviceName
        }
        if (other != null) {
            FileLogger.log("[$index] device ${device.deviceName} already in use by [${other.index}]")
            slot.statusText = getString(R.string.status_device_in_use)
            notifyUi()
            return
        }

        if (!usbManager.hasPermission(device)) {
            slot.pendingStartAfterPermission = startStream || slot.resumeOnReattach
            if (slot.permissionWaitName != device.deviceName) {
                slot.permissionWaitName = device.deviceName
                slot.permissionRetryDevice = device
                slot.permissionRetryStart = slot.pendingStartAfterPermission
                FileLogger.log("[$index] USB permission not ready for ${device.deviceName}, retry 300ms")
                schedulePermissionRetry(slot)
                return
            }
            // Still present?
            if (usbManager.deviceList[device.deviceName] == null) {
                FileLogger.log("[$index] Skip USB permission dialog — device already gone")
                slot.permissionWaitName = null
                slot.permissionRetryDevice = null
                return
            }
            FileLogger.log("[$index] Requesting USB permission for ${device.productName}")
            usbMonitor.requestPermission(usbManager, device)
            slot.statusText = getString(R.string.status_waiting_permission)
            notifyUi()
            return
        }
        slot.permissionWaitName = null
        slot.permissionRetryDevice = null
        if (AppPermissions.missingCameraAccess(this)) {
            FileLogger.log("[$index] Defer open: camera permission missing (Quest USB_CAMERA / CAMERA)")
            slot.statusText = getString(R.string.status_waiting_camera_permission)
            notifyUi()
            return
        }
        openDeviceAndLoadModes(slot, device, startStream)
    }

    private fun schedulePermissionRetry(slot: Slot) {
        slot.permissionRetryRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            slot.permissionRetryRunnable = null
            val dev = slot.permissionRetryDevice ?: return@Runnable
            val live = usbManager.deviceList[dev.deviceName]
            if (live == null) {
                FileLogger.log("[${slot.index}] USB permission retry aborted — device gone: ${dev.deviceName}")
                slot.permissionRetryDevice = null
                slot.permissionWaitName = null
                return@Runnable
            }
            prepareDevice(slot, live, startStream = slot.permissionRetryStart || slot.resumeOnReattach)
        }
        slot.permissionRetryRunnable = runnable
        handler.postDelayed(runnable, 300L)
    }

    private fun openDeviceAndLoadModes(slot: Slot, device: UsbDevice, startStream: Boolean) {
        val index = slot.index
        if (!slot.session.open(device)) {
            CaptureKeepAlive.setWantStreaming(this, index, false)
            slot.statusText = getString(R.string.status_open_failed)
            notifyUi()
            return
        }
        DeviceHistory.touch(this, device)
        val modes = slot.session.loadStreamModes()
        slot.modes = modes
        if (modes.isEmpty()) {
            slot.selectedMode = null
            slot.selectedFps = 0
            CaptureKeepAlive.setWantStreaming(this, index, false)
            slot.statusText = getString(R.string.status_no_stream_format)
            notifyUi()
            return
        }
        pickInitialMode(slot, device)
        slot.statusText = getString(R.string.status_resolutions_ready, modes.size)
        notifyUi()
        if (startStream) {
            ensureHttpRunning(slot)
            applySelectedMode(slot)
        }
    }

    private fun pickInitialMode(slot: Slot, device: UsbDevice) {
        val modes = slot.modes
        val remembered = DeviceHistory.loadMode(this, device)
        val rememberedIdx = remembered?.let {
            StreamMode.indexOfSize(modes, it.width, it.height)
        }?.takeIf { it >= 0 }
        val idx = rememberedIdx ?: StreamMode.preferredIndex(modes)
        val mode = modes[idx]
        slot.selectedMode = mode
        slot.selectedFps = when {
            remembered != null &&
                remembered.width == mode.width &&
                remembered.height == mode.height &&
                mode.fpsList.contains(remembered.fps) -> remembered.fps
            // Do not adopt a camera's "device default" frame rate blindly: it can be
            // 120fps at some tiny resolution. Aim at ~30fps unless the user picked one.
            else -> mode.nearestFps(StreamMode.DEFAULT_STREAM_FPS)
        }
        if (remembered != null && rememberedIdx != null) {
            FileLogger.log(
                "Restored remembered mode ${mode.sizeLabel} @${slot.selectedFps}fps for ${DeviceHistory.modelKey(device)}"
            )
        }
    }

    private fun applySelectedMode(slot: Slot) {
        val index = slot.index
        val mode = slot.selectedMode
        if (mode == null) {
            FileLogger.log("[$index] applySelectedMode: no resolution selected")
            CaptureKeepAlive.setWantStreaming(this, index, false)
            slot.statusText = getString(R.string.status_select_resolution)
            notifyUi()
            return
        }
        val fps = slot.selectedFps.takeIf { mode.fpsList.contains(it) }
            ?: mode.defaultFps.takeIf { mode.fpsList.contains(it) }
            ?: mode.fpsList.firstOrNull()
        if (fps == null) {
            FileLogger.log("[$index] applySelectedMode: no fps selected")
            CaptureKeepAlive.setWantStreaming(this, index, false)
            slot.statusText = getString(R.string.status_select_fps)
            notifyUi()
            return
        }
        if (!slot.isDeviceOpen) {
            CaptureKeepAlive.setWantStreaming(this, index, false)
            notifyUi()
            return
        }

        var lastRes = -1
        for ((attempt, attemptFps) in streamAttempts(slot, mode, fps)) {
            lastRes = slot.session.startStream(
                attempt.width,
                attempt.height,
                attemptFps,
                attempt.format.wire,
            )
            if (lastRes != 0) continue
            val fallback =
                attempt.width != mode.width ||
                    attempt.height != mode.height ||
                    attemptFps != fps
            slot.selectedMode = attempt
            slot.selectedFps = attemptFps
            slot.device?.let {
                DeviceHistory.saveMode(this, it, attempt.width, attempt.height, attemptFps)
            }
            slot.resumeOnReattach = false
            CaptureKeepAlive.setWantStreaming(this, index, true)
            slot.statusText = if (fallback) {
                if (attempt.format.isYuv) {
                    getString(
                        R.string.status_streaming_fallback_yuv,
                        attempt.width,
                        attempt.height,
                        attemptFps,
                    )
                } else {
                    getString(R.string.status_streaming_fallback, attempt.width, attempt.height, attemptFps)
                }
            } else if (attempt.format.isYuv) {
                getString(R.string.status_streaming_yuv, attempt.width, attempt.height, attemptFps)
            } else {
                getString(R.string.status_streaming, attempt.width, attempt.height, attemptFps)
            }
            syncCympleSlot(slot)
            notifyUi()
            return
        }

        FileLogger.log("[$index] applySelectedMode: all modes failed (last=$lastRes)")
        slot.device?.let { DeviceHistory.clearMode(this, it) }
        CaptureKeepAlive.setWantStreaming(this, index, false)
        slot.statusText = getString(R.string.status_stream_error, lastRes, mode.width, mode.height, fps)
        syncCympleSlot(slot)
        notifyUi()
    }

    private fun streamAttempts(slot: Slot, mode: StreamMode, fps: Int): List<Pair<StreamMode, Int>> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<Pair<StreamMode, Int>>()
        fun add(m: StreamMode, f: Int) {
            val key = "${m.format.wire}:${m.width}x${m.height}@$f"
            if (!seen.add(key)) return
            out.add(m to f)
        }
        add(mode, fps)
        mode.fpsList.sorted().forEach { add(mode, it) }
        slot.modes
            .filter {
                it.format == mode.format &&
                    (it.width != mode.width || it.height != mode.height)
            }
            .sortedBy { it.width * it.height }
            .forEach { other ->
                add(other, other.defaultFps)
                other.fpsList.sorted().forEach { add(other, it) }
            }
        return out
    }

    // ----------------------------------------------------------- keep-alive bits

    private fun anySlotWantsStreaming(): Boolean {
        return slots.any { CaptureKeepAlive.wantStreaming(this, it.index) }
    }

    private fun slotWantsAutoStart(slot: Slot): Boolean {
        return AutoStartPrefs.isEnabled(this) ||
            CaptureKeepAlive.wantStreaming(this, slot.index) ||
            slot.resumeOnReattach
    }

    private fun shouldStayAlive(): Boolean {
        return slots.any {
            it.isStreaming ||
                it.resumeOnReattach ||
                CaptureKeepAlive.wantStreaming(this, it.index)
        }
    }

    private fun notificationText(): String {
        val streaming = slots.filter { it.isStreaming }
        return when {
            streaming.size >= 2 -> getString(R.string.notify_two_streams)
            streaming.size == 1 -> streaming.first().statusText
            slots.any { it.resumeOnReattach } -> getString(R.string.notify_waiting_reconnect)
            else -> getString(R.string.notify_waiting)
        }
    }

    private fun syncKeepAlive() {
        if (slots.any { it.isStreaming }) {
            locks.acquire()
        } else {
            locks.release()
        }
        if (shouldStayAlive()) {
            enterForeground(notificationText())
        } else if (foreground) {
            leaveForeground()
        }
    }

    private fun ensureHttpRunning(slot: Slot) {
        if (slot.http.isRunning) return
        val ok = slot.http.ensureStarted()
        FileLogger.log("[${slot.index}] HTTP ensureStarted ok=$ok port=${slot.http.port}")
        notifyUi()
    }

    private fun isAutoOpenSuppressed(slot: Slot): Boolean {
        return System.currentTimeMillis() < slot.suppressAutoOpenUntilMs
    }

    /**
     * Hot-unplug suppression delays auto reopen. Re-run the refresh once the last
     * suppression window expires, otherwise the camera would sit idle until the
     * next USB event.
     */
    private fun scheduleRefreshAfterSuppression() {
        val now = System.currentTimeMillis()
        val wait = slots.maxOf { it.suppressAutoOpenUntilMs - now }.coerceAtLeast(0L)
        if (wait <= 0L) return
        handler.removeCallbacks(usbRetryRunnable)
        FileLogger.log("Defer USB reopen ${wait}ms (hot-unplug settle)")
        handler.postDelayed(usbRetryRunnable, wait + 50L)
    }

    private fun requestKeepAlive(text: String? = null) {
        if (text != null) pendingNotifyText = text
        val intent = Intent(this, CaptureService::class.java).setAction(ACTION_KEEP)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (t: Throwable) {
            FileLogger.log("startForegroundService: ${t.message}")
            startForegroundInternal(
                pendingNotifyText ?: getString(R.string.notify_waiting),
            )
        }
    }

    private fun enterForeground(text: String) {
        pendingNotifyText = text
        if (foreground) {
            startForegroundInternal(text)
            return
        }
        requestKeepAlive(text)
    }

    private fun startForegroundInternal(text: String) {
        val notification = CaptureNotification.build(
            this,
            text,
            showStop = shouldStayAlive(),
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    CaptureNotification.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(CaptureNotification.NOTIFICATION_ID, notification)
            }
            foreground = true
        } catch (t: Throwable) {
            FileLogger.log("startForeground: ${t.message}")
        }
    }

    private fun leaveForeground() {
        if (!foreground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun notifyUi() {
        syncKeepAlive()
        handler.post {
            listeners.forEach { it.onCaptureStateChanged() }
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.omoai.simpleuvcstreamer.USB_PERMISSION"
        const val ACTION_KEEP = "com.omoai.simpleuvcstreamer.action.KEEP"
        const val ACTION_STOP = "com.omoai.simpleuvcstreamer.action.STOP"
    }
}
