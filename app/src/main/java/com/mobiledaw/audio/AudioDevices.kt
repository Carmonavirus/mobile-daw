package com.mobiledaw.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * The speakers, headphones and mics the phone has, and which ones the app plays to / records from
 * (Settings → Audio devices). Each device has a [AudioPort.key] that stays the same when it is
 * unplugged and plugged back in (Android hands out a new id each time), so a choice is remembered.
 * A null key means Automatic: Android picks, the way other apps are routed.
 *
 * The web app has its own version of this file with the same API (web/.../twin/AudioDevices.kt).
 */
object AudioDevices {
    /** Shown under the Output picker when the platform can't route the app's sound itself. */
    val outputNote: String? = null
    val inputNote: String? = null
    /** Web only: the browser hides device names until the microphone is allowed. */
    val needsPermissionForNames: Boolean get() = false
    fun askForNames(done: () -> Unit) = done()

    private var outputKey: String? = null
    private var inputKey: String? = null
    private var appContext: Context? = null
    private var callback: AudioDeviceCallback? = null

    /** The mic [Recorder] should use, null = Android's default. Resolved again for every take. */
    @Volatile var preferredInput: AudioDeviceInfo? = null
        private set

    /** Lists outputs and inputs; [done] is called right away (on the web, once the browser answers). */
    fun list(context: Context, done: (outputs: List<AudioPort>, inputs: List<AudioPort>) -> Unit) {
        val am = manager(context)
        done(
            ports(am, AudioManager.GET_DEVICES_OUTPUTS, OUTPUT_TYPES).map { it.first },
            ports(am, AudioManager.GET_DEVICES_INPUTS, INPUT_TYPES).map { it.first },
        )
    }

    /** Plays to the output with [key] (null = Automatic). Falls back to Automatic while it is missing. */
    fun useOutput(context: Context, key: String?) {
        outputKey = key
        val device = key?.let { find(manager(context), AudioManager.GET_DEVICES_OUTPUTS, OUTPUT_TYPES, it) }
        NativeEngine.setOutputDevice(device?.id ?: 0)
    }

    /** Records from the input with [key] (null = Automatic) from the next take on. */
    fun useInput(context: Context, key: String?) {
        inputKey = key
        preferredInput = key?.let { find(manager(context), AudioManager.GET_DEVICES_INPUTS, INPUT_TYPES, it) }
    }

    /**
     * Calls [onChange] (on the main thread) whenever a device is plugged in or out, after moving
     * the sound back onto the chosen device if it just came back.
     */
    fun watch(context: Context, onChange: () -> Unit) {
        val app = context.applicationContext
        appContext = app
        callback?.let { manager(app).unregisterAudioDeviceCallback(it) }
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = changed()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = changed()
            private fun changed() {
                useOutput(app, outputKey)
                useInput(app, inputKey)
                onChange()
            }
        }
        callback = cb
        manager(app).registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
    }

    /**
     * Bluetooth headset mics only send sound while the earbuds are in "call" mode. [Recorder]
     * calls this before a take and [endCapture] after; it does nothing for other mics.
     */
    fun beginCapture() {
        val device = preferredInput ?: return
        if (device.type !in CALL_MICS) return
        val am = appContext?.let { manager(it) } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == device.type }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
            }
        }
    }

    fun endCapture() {
        val device = preferredInput ?: return
        if (device.type !in CALL_MICS) return
        val am = appContext?.let { manager(it) } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
        }
    }

    private fun manager(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun find(am: AudioManager, flags: Int, types: Set<Int>, key: String): AudioDeviceInfo? =
        ports(am, flags, types).firstOrNull { it.first.key == key }?.second

    /** Usable devices, one per key, in a friendly order (built-in first, then wired, USB, Bluetooth). */
    private fun ports(am: AudioManager, flags: Int, types: Set<Int>): List<Pair<AudioPort, AudioDeviceInfo>> {
        val devices = runCatching { am.getDevices(flags).toList() }.getOrDefault(emptyList())
            .filter { it.type in types }
        val builtInMics = devices.count { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val seen = HashSet<String>()
        return devices
            .sortedBy { types.indexOf(it.type) }
            .mapNotNull { d ->
                val name = d.productName?.toString()?.trim().orEmpty()
                val address = if (Build.VERSION.SDK_INT >= 28) d.address.orEmpty() else ""
                val key = when (d.type) {
                    // The model name is the phone's own, so the type alone is enough.
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "${d.type}"
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "${d.type}:$address"
                    else -> "${d.type}:$name:$address"
                }
                if (!seen.add(key)) return@mapNotNull null
                val label = when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
                    AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                        if (builtInMics > 1 && address.isNotBlank()) "Phone mic (${address.replaceFirstChar { it.uppercase() }})" else "Phone mic"
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> if (flags == AudioManager.GET_DEVICES_INPUTS) "Headset mic (wired)" else "Wired headphones"
                    else -> name.ifEmpty { typeName(d.type) }
                }
                val detail = when (d.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_MIC,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> ""
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET ->
                        if (flags == AudioManager.GET_DEVICES_INPUTS) "Bluetooth mic, records at call quality" else "Bluetooth"
                    else -> typeName(d.type)
                }
                AudioPort(key, label, detail) to d
            }
    }

    private fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER -> "Bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        else -> "Audio device"
    }

    private const val TYPE_BLE_HEADSET = 26 // API 31
    private const val TYPE_BLE_SPEAKER = 27 // API 31
    private val CALL_MICS = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET)

    // In the order they are listed. Earpiece, call-only (SCO) outputs, telephony and system
    // devices are left out: the app can't usefully play music through them.
    private val OUTPUT_TYPES = linkedSetOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        TYPE_BLE_HEADSET,
        TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )
    private val INPUT_TYPES = linkedSetOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        TYPE_BLE_HEADSET,
    )
}
