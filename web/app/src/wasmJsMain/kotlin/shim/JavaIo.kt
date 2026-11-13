// Web stand-ins for the few java.io classes the app's shared code uses. Files live in an
// in-memory file system (Vfs) that is saved to the browser's storage (IndexedDB).
@file:Suppress("unused", "PackageDirectoryMismatch")

package java.io

import com.mobiledaw.web.Vfs

open class IOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)
class FileNotFoundException(message: String? = null) : IOException(message)

interface Closeable : AutoCloseable { override fun close() }

class File(path: String) : Comparable<File> {
    constructor(parent: File?, child: String) : this(if (parent == null) child else Vfs.join(parent.path, child))
    constructor(parent: String?, child: String) : this(if (parent == null) child else Vfs.join(parent, child))

    val path: String = Vfs.normalize(path)
    val name: String get() = path.substringAfterLast('/')
    val extension: String get() = name.substringAfterLast('.', "")
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')
    val parent: String? get() = if (path.contains('/')) path.substringBeforeLast('/').ifEmpty { "/" } else null
    val parentFile: File? get() = parent?.let { File(it) }
    val absolutePath: String get() = path
    val absoluteFile: File get() = this
    val canonicalPath: String get() = path
    val canonicalFile: File get() = this

    fun exists(): Boolean = Vfs.exists(path)
    val isDirectory: Boolean get() = Vfs.isDir(path)
    val isFile: Boolean get() = Vfs.isFile(path)
    fun isDirectory(): Boolean = isDirectory
    fun length(): Long = Vfs.size(path)
    fun lastModified(): Long = Vfs.modified(path)
    fun setLastModified(time: Long): Boolean = Vfs.touch(path, time)
    fun mkdirs(): Boolean = Vfs.mkdirs(path)
    fun mkdir(): Boolean = Vfs.mkdirs(path)
    fun createNewFile(): Boolean = if (exists()) false else { Vfs.write(path, ByteArray(0)); true }
    fun delete(): Boolean = Vfs.delete(path, recursive = false)
    fun deleteRecursively(): Boolean = Vfs.delete(path, recursive = true)
    fun renameTo(dest: File): Boolean = Vfs.rename(path, dest.path)
    fun list(): Array<String>? = if (isDirectory) Vfs.children(path).map { it.substringAfterLast('/') }.toTypedArray() else null
    fun listFiles(): Array<File>? = if (isDirectory) Vfs.children(path).map { File(it) }.toTypedArray() else null
    fun listFiles(filter: (File) -> Boolean): Array<File>? = listFiles()?.filter(filter)?.toTypedArray()
    fun listFiles(filter: FileFilter): Array<File>? = listFiles()?.filter { filter.accept(it) }?.toTypedArray()

    fun readBytes(): ByteArray = Vfs.read(path) ?: throw FileNotFoundException(path)
    fun readText(): String = readBytes().decodeToString()
    fun writeBytes(bytes: ByteArray) = Vfs.write(path, bytes.copyOf())
    fun writeText(text: String) = Vfs.write(path, text.encodeToByteArray())
    fun appendBytes(bytes: ByteArray) = Vfs.write(path, (Vfs.read(path) ?: ByteArray(0)) + bytes)
    fun readLines(): List<String> = readText().lines()
    fun inputStream(): InputStream = FileInputStream(this)
    fun outputStream(): FileOutputStream = FileOutputStream(this)

    fun copyTo(target: File, overwrite: Boolean = false, bufferSize: Int = 0): File {
        if (target.exists() && !overwrite) throw IOException("exists: ${target.path}")
        if (isDirectory) target.mkdirs() else target.writeBytes(readBytes())
        return target
    }

    fun copyRecursively(target: File, overwrite: Boolean = false): Boolean {
        walkTopDown().forEach { f ->
            val t = File(target, f.toRelativeString(this))
            if (f.isDirectory) t.mkdirs() else f.copyTo(t, overwrite)
        }
        return true
    }

    fun relativeTo(base: File): File = File(toRelativeString(base))
    fun toRelativeString(base: File): String =
        if (path == base.path) "" else if (path.startsWith(base.path + "/")) path.removePrefix(base.path + "/") else path

    fun resolve(relative: String): File = File(this, relative)
    fun walkTopDown(): Sequence<File> = sequence {
        yield(this@File)
        if (isDirectory) for (c in listFiles().orEmpty()) yieldAll(c.walkTopDown())
    }
    fun walk(): Sequence<File> = walkTopDown()
    fun walkBottomUp(): Sequence<File> = walkTopDown().toList().reversed().asSequence()

    override fun compareTo(other: File): Int = path.compareTo(other.path)
    override fun equals(other: Any?): Boolean = other is File && other.path == path
    override fun hashCode(): Int = path.hashCode()
    override fun toString(): String = path
}

fun interface FileFilter { fun accept(f: File): Boolean }

abstract class InputStream : Closeable {
    abstract fun read(): Int
    open fun read(b: ByteArray): Int = read(b, 0, b.size)
    open fun read(b: ByteArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len) {
            val c = read()
            if (c < 0) break
            b[off + n] = c.toByte()
            n++
        }
        return if (n == 0 && len > 0) -1 else n
    }
    open fun readBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
    open fun available(): Int = 0
    open fun skip(n: Long): Long {
        var i = 0L
        while (i < n && read() >= 0) i++
        return i
    }
    fun copyTo(out: OutputStream, bufferSize: Int = 8192): Long {
        val buf = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return total
    }
    override fun close() {}
    fun buffered(size: Int = 8192): InputStream = this
}

open class ByteArrayInputStream(private val data: ByteArray) : InputStream() {
    private var pos = 0
    override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(len, data.size - pos)
        data.copyInto(b, off, pos, pos + n)
        pos += n
        return n
    }
    override fun available(): Int = data.size - pos
    override fun skip(n: Long): Long {
        val k = minOf(n, (data.size - pos).toLong()).toInt()
        pos += k
        return k.toLong()
    }
}

class FileInputStream(file: File) : ByteArrayInputStream(file.readBytes())

abstract class OutputStream : Closeable {
    abstract fun write(b: Int)
    open fun write(b: ByteArray) = write(b, 0, b.size)
    open fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt())
    }
    open fun flush() {}
    override fun close() {}
    fun buffered(size: Int = 8192): OutputStream = this
}

open class ByteArrayOutputStream(size: Int = 64) : OutputStream() {
    private var buf = ByteArray(maxOf(16, size))
    private var count = 0
    private fun ensure(extra: Int) {
        if (count + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, count + extra))
    }
    override fun write(b: Int) {
        ensure(1)
        buf[count++] = b.toByte()
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        ensure(len)
        b.copyInto(buf, count, off, off + len)
        count += len
    }
    fun toByteArray(): ByteArray = buf.copyOf(count)
    fun size(): Int = count
    fun reset() { count = 0 }
}

class FileOutputStream(private val file: File, private val append: Boolean = false) : ByteArrayOutputStream() {
    constructor(path: String) : this(File(path))
    private var closed = false
    override fun flush() {
        val prev = if (append) Vfs.read(file.path) ?: ByteArray(0) else ByteArray(0)
        Vfs.write(file.path, prev + toByteArray())
    }
    override fun close() {
        if (closed) return
        closed = true
        flush()
    }
}

class BufferedOutputStream(private val out: OutputStream, size: Int = 8192) : OutputStream() {
    override fun write(b: Int) = out.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun flush() = out.flush()
    override fun close() = out.close()
}

class BufferedInputStream(private val inp: InputStream, size: Int = 8192) : InputStream() {
    override fun read(): Int = inp.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = inp.read(b, off, len)
    override fun close() = inp.close()
}

/** Random access over a file's bytes; written back when closed. */
class RandomAccessFile(private val file: File, mode: String) : Closeable {
    private var data: ByteArray = Vfs.read(file.path) ?: if (mode.contains('w')) ByteArray(0) else throw FileNotFoundException(file.path)
    private var size = data.size
    private var pos = 0L
    private val writable = mode.contains('w')
    val filePointer: Long get() = pos
    fun getFilePointer(): Long = pos
    fun length(): Long = size.toLong()
    fun setLength(n: Long) {
        ensure(n.toInt())
        size = n.toInt()
        if (pos > size) pos = size.toLong()
    }
    fun seek(p: Long) { pos = p }
    private fun ensure(n: Int) {
        if (n > data.size) data = data.copyOf(maxOf(n, data.size * 2))
    }
    fun write(b: ByteArray) = write(b, 0, b.size)
    fun write(b: ByteArray, off: Int, len: Int) {
        val p = pos.toInt()
        ensure(p + len)
        b.copyInto(data, p, off, off + len)
        pos += len
        if (pos > size) size = pos.toInt()
    }
    fun write(b: Int) = write(byteArrayOf(b.toByte()))
    fun read(): Int = if (pos < size) data[pos++.toInt()].toInt() and 0xff else -1
    fun read(b: ByteArray, off: Int = 0, len: Int = b.size): Int {
        if (pos >= size) return -1
        val n = minOf(len, size - pos.toInt())
        data.copyInto(b, off, pos.toInt(), pos.toInt() + n)
        pos += n
        return n
    }
    fun readFully(b: ByteArray) { read(b, 0, b.size) }
    override fun close() {
        if (writable) Vfs.write(file.path, data.copyOf(size))
    }
}
