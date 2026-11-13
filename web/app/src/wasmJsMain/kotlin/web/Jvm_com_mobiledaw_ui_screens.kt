// JVM built-ins that the app's code in package com.mobiledaw.ui.screens uses without an import, for the web.
@file:Suppress("unused", "NOTHING_TO_INLINE", "PackageDirectoryMismatch")

package com.mobiledaw.ui.screens

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
