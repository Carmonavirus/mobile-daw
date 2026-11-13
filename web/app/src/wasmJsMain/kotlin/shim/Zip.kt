@file:Suppress("unused", "PackageDirectoryMismatch")

package java.util.zip

import com.mobiledaw.web.Unzip

class ZipEntry(val name: String, internal val data: ByteArray?) {
    val isDirectory: Boolean get() = name.endsWith("/")
    fun isDirectory(): Boolean = isDirectory
    val size: Long get() = data?.size?.toLong() ?: 0L
    fun getSize(): Long = size
}

/** Reads a whole .zip at once (with the fflate library) and hands out its entries in order. */
class ZipInputStream(input: java.io.InputStream) : java.io.InputStream() {
    private val entries: List<ZipEntry> = Unzip.unzip(input.readBytes())
    private var index = -1
    private var current: java.io.ByteArrayInputStream? = null

    val nextEntry: ZipEntry? get() = getNextEntry()
    fun getNextEntry(): ZipEntry? {
        index++
        val e = entries.getOrNull(index)
        current = e?.data?.let { java.io.ByteArrayInputStream(it) }
        return e
    }
    fun closeEntry() { current = null }
    override fun read(): Int = current?.read() ?: -1
    override fun read(b: ByteArray, off: Int, len: Int): Int = current?.read(b, off, len) ?: -1
}

class ZipOutputStream(private val out: java.io.OutputStream) : java.io.OutputStream() {
    private val files = ArrayList<Pair<String, ByteArray>>()
    private var name: String? = null
    private var buf = java.io.ByteArrayOutputStream()
    fun putNextEntry(e: ZipEntry) { closeEntry(); name = e.name; buf = java.io.ByteArrayOutputStream() }
    fun closeEntry() { name?.let { files += it to buf.toByteArray() }; name = null }
    override fun write(b: Int) = buf.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
    override fun close() {
        closeEntry()
        out.write(Unzip.zip(files))
        out.close()
    }
}
