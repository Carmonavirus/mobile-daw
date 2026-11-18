@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package android.database

/** A one-row cursor (display name and size of a picked file). */
class Cursor(private val row: Map<String, String>) : AutoCloseable {
    private val cols = row.keys.toList()
    fun moveToFirst(): Boolean = true
    fun getColumnIndex(name: String): Int = cols.indexOf(name)
    fun getColumnIndexOrThrow(name: String): Int = cols.indexOf(name)
    fun getString(i: Int): String? = cols.getOrNull(i)?.let { row[it] }
    fun getLong(i: Int): Long = getString(i)?.toLongOrNull() ?: 0L
    fun isNull(i: Int): Boolean = getString(i) == null
    override fun close() {}
}
