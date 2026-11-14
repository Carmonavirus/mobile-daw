// Small bridges between Kotlin/Wasm and the browser (see resources/platform.js).
@file:Suppress("unused")

package com.mobiledaw.web

import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int32Array
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

fun ByteArray.toJs(): Int8Array {
    val out = Int8Array(size)
    for (i in indices) out[i] = this[i]
    return out
}

fun Int8Array.toKotlin(): ByteArray = ByteArray(length) { this[it] }

fun FloatArray.toJs(): Float32Array {
    val out = Float32Array(size)
    for (i in indices) out[i] = this[i]
    return out
}

fun Float32Array.toKotlin(): FloatArray = FloatArray(length) { this[it] }
fun Float32Array.copyInto(dst: FloatArray) {
    val n = minOf(length, dst.size)
    for (i in 0 until n) dst[i] = this[i]
}

fun IntArray.toJs(): Int32Array {
    val out = Int32Array(size)
    for (i in indices) out[i] = this[i]
    return out
}
