package com.mobiledaw.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pitch-shifted copies of audio clips (Stretch / Vocal), kept in the app's cache folder so a
 * long take is only rendered once per pitch. Raw little-endian floats after a small header.
 * Android may clear the folder at any time; the copy is then simply rendered again.
 */
object StretchCache {
    private const val MAGIC = 0x53545231 // "STR1"
    private const val MAX_BYTES = 400L * 1024 * 1024

    fun read(file: File): WavDecoder.Decoded? {
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.int != MAGIC) return null
            val rate = buf.float
            val count = buf.int
            if (count < 0 || buf.remaining() != count * 4) return null
            val frames = FloatArray(count)
            buf.asFloatBuffer().get(frames)
            file.setLastModified(System.currentTimeMillis())
            WavDecoder.Decoded(frames, rate)
        }.getOrNull()
    }

    fun write(file: File, decoded: WavDecoder.Decoded) {
        runCatching {
            val dir = file.parentFile ?: return
            dir.mkdirs()
            val buf = ByteBuffer.allocate(12 + decoded.frames.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(MAGIC).putFloat(decoded.sampleRate).putInt(decoded.frames.size)
            buf.asFloatBuffer().put(decoded.frames)
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(buf.array())
            tmp.renameTo(file)
            prune(dir)
        }
    }

    /** Drops the least recently used copies once the folder passes [MAX_BYTES]. */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
