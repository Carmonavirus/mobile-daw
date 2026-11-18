@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package android.media

import com.mobiledaw.web.DecodedAudio
import com.mobiledaw.web.Vfs
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioFormat {
    const val ENCODING_PCM_16BIT = 2
    const val ENCODING_PCM_FLOAT = 4
    const val CHANNEL_IN_MONO = 16
    const val CHANNEL_OUT_STEREO = 12
}

class AudioTimestamp {
    var framePosition = 0L
    var nanoTime = 0L
}

class AudioDeviceInfo(val id: Int, val type: Int, val productName: CharSequence?) {
    companion object {
        const val TYPE_BUILTIN_SPEAKER = 2
        const val TYPE_WIRED_HEADSET = 3
        const val TYPE_WIRED_HEADPHONES = 4
        const val TYPE_BLUETOOTH_A2DP = 8
        const val TYPE_USB_DEVICE = 11
        const val TYPE_USB_HEADSET = 22
        const val TYPE_HEARING_AID = 23
    }
}

/** The browser doesn't say which output is used; the app then assumes the speaker. */
class AudioManager {
    fun getDevices(flags: Int): Array<AudioDeviceInfo> = emptyArray()
    companion object {
        const val GET_DEVICES_OUTPUTS = 2
    }
}

class MediaFormat internal constructor(private val values: Map<String, Any>) {
    fun getString(key: String): String? = values[key] as? String
    fun getInteger(key: String): Int = values[key] as? Int ?: throw NullPointerException(key)
    fun containsKey(key: String): Boolean = values.containsKey(key)
    companion object {
        const val KEY_MIME = "mime"
        const val KEY_SAMPLE_RATE = "sample-rate"
        const val KEY_CHANNEL_COUNT = "channel-count"
        const val KEY_PCM_ENCODING = "pcm-encoding"
    }
}

/**
 * Reads an audio file the browser has already decoded (see [DecodedAudio]): each "sample" is a
 * block of interleaved float PCM, which [MediaCodec] hands straight back as decoder output.
 */
class MediaExtractor {
    private var audio: DecodedAudio.Entry? = null
    private var frame = 0
    val trackCount: Int get() = if (audio != null) 1 else 0
    fun getTrackCount(): Int = trackCount
    val sampleTime: Long get() = audio?.let { frame * 1_000_000L / it.rate } ?: 0L
    fun getSampleTime(): Long = sampleTime

    fun setDataSource(path: String) {
        val bytes = Vfs.read(Vfs.normalize(path)) ?: throw java.io.FileNotFoundException(path)
        audio = DecodedAudio.find(bytes) ?: throw java.io.IOException("The browser can't read this audio file")
        frame = 0
    }

    fun getTrackFormat(index: Int): MediaFormat {
        val a = audio ?: throw IllegalStateException()
        return MediaFormat(mapOf(MediaFormat.KEY_MIME to "audio/raw", MediaFormat.KEY_SAMPLE_RATE to a.rate, MediaFormat.KEY_CHANNEL_COUNT to a.channels.size))
    }

    fun selectTrack(index: Int) {}

    fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        val a = audio ?: return -1
        if (frame >= a.frames) return -1
        val ch = a.channels.size
        val n = minOf(BLOCK_FRAMES, a.frames - frame)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        var at = offset
        for (i in 0 until n) for (c in 0 until ch) {
            buffer.putFloat(at, a.channels[c][frame + i])
            at += 4
        }
        buffer.position(offset)
        buffer.limit(at)
        return at - offset
    }

    fun advance(): Boolean {
        val a = audio ?: return false
        frame = minOf(a.frames, frame + BLOCK_FRAMES)
        return frame < a.frames
    }

    fun release() {
        audio = null
    }

    internal companion object {
        const val BLOCK_FRAMES = 4096
    }
}

/** Passes [MediaExtractor]'s already decoded blocks through as float PCM. */
class MediaCodec private constructor() {
    class BufferInfo {
        var offset = 0
        var size = 0
        var presentationTimeUs = 0L
        var flags = 0
    }

    private var format: MediaFormat? = null
    private val input = ByteBuffer.allocate(MediaExtractor.BLOCK_FRAMES * 8 * 4).order(ByteOrder.LITTLE_ENDIAN)
    private var queuedSize = -1
    private var queuedFlags = 0
    private var formatSent = false

    val outputFormat: MediaFormat
        get() {
            val f = format ?: throw IllegalStateException()
            return MediaFormat(
                mapOf(
                    MediaFormat.KEY_SAMPLE_RATE to f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    MediaFormat.KEY_CHANNEL_COUNT to f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    MediaFormat.KEY_PCM_ENCODING to AudioFormat.ENCODING_PCM_FLOAT,
                ),
            )
        }
    fun getOutputFormat(): MediaFormat = outputFormat

    fun configure(format: MediaFormat, surface: Any?, crypto: Any?, flags: Int) {
        this.format = format
    }
    fun start() {}
    fun stop() {}
    fun release() {}

    fun dequeueInputBuffer(timeoutUs: Long): Int = if (queuedSize < 0) 0 else INFO_TRY_AGAIN_LATER
    fun getInputBuffer(index: Int): ByteBuffer? = input.clear()

    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        queuedSize = size
        queuedFlags = flags
    }

    fun dequeueOutputBuffer(info: BufferInfo, timeoutUs: Long): Int {
        if (!formatSent) {
            formatSent = true
            return INFO_OUTPUT_FORMAT_CHANGED
        }
        if (queuedSize < 0) return INFO_TRY_AGAIN_LATER
        info.offset = 0
        info.size = queuedSize
        info.flags = queuedFlags
        return 0
    }

    fun getOutputBuffer(index: Int): ByteBuffer? = input.position(0).limit(maxOf(0, queuedSize))

    fun releaseOutputBuffer(index: Int, render: Boolean) {
        queuedSize = -1
        queuedFlags = 0
    }

    companion object {
        const val BUFFER_FLAG_END_OF_STREAM = 4
        const val INFO_TRY_AGAIN_LATER = -1
        const val INFO_OUTPUT_FORMAT_CHANGED = -2
        fun createDecoderByType(mime: String): MediaCodec = MediaCodec()
    }
}

object MediaRecorder {
    object AudioSource {
        const val MIC = 1
        const val UNPROCESSED = 9
        const val VOICE_RECOGNITION = 6
    }
}
