package com.mobiledaw.audio

/**
 * A speaker, pair of headphones or mic the app can use (see [AudioDevices]). [key] identifies it
 * across unplugging and restarts and is what Settings remembers; [detail] is a short hint
 * ("Bluetooth", "USB") shown under the name, or empty.
 */
data class AudioPort(val key: String, val label: String, val detail: String = "")
