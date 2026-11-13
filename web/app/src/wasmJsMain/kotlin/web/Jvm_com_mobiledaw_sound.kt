// JVM built-ins that the app's code in package com.mobiledaw.sound uses without an import, for the web.
@file:Suppress("unused", "NOTHING_TO_INLINE", "PackageDirectoryMismatch")

package com.mobiledaw.sound

import kotlin.math.pow

internal inline fun String.format(vararg args: Any?): String = com.mobiledaw.web.formatString(this, args)
internal val kotlinx.coroutines.Dispatchers.IO: kotlinx.coroutines.CoroutineDispatcher get() = kotlinx.coroutines.Dispatchers.Default
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
internal annotation class Volatile

internal object System {
    fun currentTimeMillis(): Long = com.mobiledaw.web.Clock.now()
    fun nanoTime(): Long = com.mobiledaw.web.Clock.nanos()
    fun loadLibrary(name: String) {}
    fun arraycopy(src: FloatArray, srcPos: Int, dst: FloatArray, dstPos: Int, length: Int) { src.copyInto(dst, dstPos, srcPos, srcPos + length) }
}

internal object Math {
    fun pow(a: Double, b: Double): Double = a.pow(b)
    fun round(a: Double): Long = kotlin.math.round(a).toLong()
    fun round(a: Float): Int = kotlin.math.round(a).toInt()
    fun toRadians(a: Double): Double = a * kotlin.math.PI / 180.0
    fun toDegrees(a: Double): Double = a * 180.0 / kotlin.math.PI
    fun abs(a: Int): Int = kotlin.math.abs(a)
    fun abs(a: Float): Float = kotlin.math.abs(a)
    fun abs(a: Double): Double = kotlin.math.abs(a)
    fun log10(a: Double): Double = kotlin.math.log10(a)
    fun sqrt(a: Double): Double = kotlin.math.sqrt(a)
    fun max(a: Int, b: Int): Int = kotlin.math.max(a, b)
    fun min(a: Int, b: Int): Int = kotlin.math.min(a, b)
    fun floorMod(a: Int, b: Int): Int = a.mod(b)
}

internal inline fun <R> synchronized(lock: Any, block: () -> R): R = block()
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
internal annotation class Synchronized
internal object Charsets {
    val UTF_8 = java.nio.charset.Charset("UTF-8")
    val US_ASCII = java.nio.charset.Charset("US-ASCII")
}
internal inline fun String.toByteArray(charset: java.nio.charset.Charset = Charsets.UTF_8): ByteArray = encodeToByteArray()

/** java.util.LinkedHashMap with access order and removeEldestEntry (Kotlin's own is final on the web). */
internal open class LinkedHashMap<K, V> private constructor(private val inner: kotlin.collections.LinkedHashMap<K, V>, private val accessOrder: Boolean) : MutableMap<K, V> by inner {
    constructor() : this(kotlin.collections.LinkedHashMap(), false)
    constructor(initialCapacity: Int) : this(kotlin.collections.LinkedHashMap(initialCapacity), false)
    constructor(initialCapacity: Int, loadFactor: Float) : this(kotlin.collections.LinkedHashMap(initialCapacity), false)
    constructor(initialCapacity: Int, loadFactor: Float, accessOrder: Boolean) : this(kotlin.collections.LinkedHashMap(initialCapacity), accessOrder)

    protected open fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = false

    override fun get(key: K): V? {
        if (!accessOrder || !inner.containsKey(key)) return inner[key]
        @Suppress("UNCHECKED_CAST") val v = inner.remove(key) as V
        inner[key] = v
        return v
    }

    override fun put(key: K, value: V): V? {
        val old = if (accessOrder) inner.remove(key) else null
        val prev = inner.put(key, value) ?: old
        val eldest = inner.entries.firstOrNull()
        if (eldest != null && removeEldestEntry(eldest)) inner.remove(eldest.key)
        return prev
    }

    override fun putAll(from: Map<out K, V>) { for ((k, v) in from) put(k, v) }
    override fun toString(): String = inner.toString()
}
internal fun String(bytes: ByteArray): String = bytes.decodeToString()
