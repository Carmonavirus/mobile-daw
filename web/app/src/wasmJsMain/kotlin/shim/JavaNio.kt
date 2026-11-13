@file:Suppress("unused", "PackageDirectoryMismatch")

package java.nio

class ByteOrder private constructor(private val name: String) {
    override fun toString() = name
    companion object {
        val LITTLE_ENDIAN = ByteOrder("LITTLE_ENDIAN")
        val BIG_ENDIAN = ByteOrder("BIG_ENDIAN")
        fun nativeOrder(): ByteOrder = LITTLE_ENDIAN
    }
}

/** java.nio.ByteBuffer over a byte array: the absolute and relative get/put methods the app uses. */
class ByteBuffer private constructor(private val data: ByteArray, private val offset: Int, private val cap: Int) {
    private var order = ByteOrder.BIG_ENDIAN
    private var pos = 0
    private var lim = cap

    fun order(o: ByteOrder): ByteBuffer { order = o; return this }
    fun order(): ByteOrder = order
    fun array(): ByteArray = data
    fun arrayOffset(): Int = offset
    fun capacity(): Int = cap
    fun position(): Int = pos
    fun position(p: Int): ByteBuffer { pos = p; return this }
    fun limit(): Int = lim
    fun limit(l: Int): ByteBuffer { lim = l; if (pos > l) pos = l; return this }
    fun remaining(): Int = lim - pos
    fun hasRemaining(): Boolean = pos < lim
    fun clear(): ByteBuffer { pos = 0; lim = cap; return this }
    fun flip(): ByteBuffer { lim = pos; pos = 0; return this }
    fun rewind(): ByteBuffer { pos = 0; return this }

    private val little get() = order === ByteOrder.LITTLE_ENDIAN
    private fun at(i: Int) = data[offset + i].toInt() and 0xff

    private fun readN(i: Int, n: Int): Long {
        var v = 0L
        for (k in 0 until n) {
            val b = at(i + if (little) k else n - 1 - k).toLong()
            v = v or (b shl (8 * k))
        }
        return v
    }
    private fun writeN(i: Int, n: Int, v: Long) {
        for (k in 0 until n) {
            val b = ((v ushr (8 * k)) and 0xff).toByte()
            data[offset + i + if (little) k else n - 1 - k] = b
        }
    }

    fun get(): Byte = data[offset + pos++]
    fun get(i: Int): Byte = data[offset + i]
    fun get(dst: ByteArray): ByteBuffer = get(dst, 0, dst.size)
    fun get(dst: ByteArray, off: Int, len: Int): ByteBuffer {
        data.copyInto(dst, off, offset + pos, offset + pos + len)
        pos += len
        return this
    }
    fun getShort(): Short = readN(pos, 2).toShort().also { pos += 2 }
    fun getShort(i: Int): Short = readN(i, 2).toShort()
    fun getInt(): Int = readN(pos, 4).toInt().also { pos += 4 }
    fun getInt(i: Int): Int = readN(i, 4).toInt()
    fun getLong(): Long = readN(pos, 8).also { pos += 8 }
    fun getLong(i: Int): Long = readN(i, 8)
    fun getFloat(): Float = Float.fromBits(getInt())
    fun getFloat(i: Int): Float = Float.fromBits(getInt(i))

    fun put(b: Byte): ByteBuffer { data[offset + pos++] = b; return this }
    fun put(i: Int, b: Byte): ByteBuffer { data[offset + i] = b; return this }
    fun put(src: ByteArray): ByteBuffer = put(src, 0, src.size)
    fun put(src: ByteArray, off: Int, len: Int): ByteBuffer {
        src.copyInto(data, offset + pos, off, off + len)
        pos += len
        return this
    }
    fun put(src: ByteBuffer): ByteBuffer {
        val n = src.remaining()
        for (k in 0 until n) put(src.get())
        return this
    }
    fun putShort(v: Short): ByteBuffer { writeN(pos, 2, v.toLong()); pos += 2; return this }
    fun putShort(i: Int, v: Short): ByteBuffer { writeN(i, 2, v.toLong()); return this }
    fun putInt(v: Int): ByteBuffer { writeN(pos, 4, v.toLong()); pos += 4; return this }
    fun putInt(i: Int, v: Int): ByteBuffer { writeN(i, 4, v.toLong()); return this }
    fun putLong(v: Long): ByteBuffer { writeN(pos, 8, v); pos += 8; return this }
    fun putFloat(v: Float): ByteBuffer = putInt(v.toBits())
    fun putFloat(i: Int, v: Float): ByteBuffer = putInt(i, v.toBits())

    companion object {
        fun allocate(n: Int) = ByteBuffer(ByteArray(n), 0, n)
        fun allocateDirect(n: Int) = allocate(n)
        fun wrap(b: ByteArray) = ByteBuffer(b, 0, b.size)
        fun wrap(b: ByteArray, off: Int, len: Int) = ByteBuffer(b, 0, b.size).position(off).limit(off + len)
    }
}
