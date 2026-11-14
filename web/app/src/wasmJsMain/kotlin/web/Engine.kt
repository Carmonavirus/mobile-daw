// Calls into the C++ engine (engine.wasm) through resources/platform.js. The page keeps two copies
// of the engine: one in the AudioWorklet that makes the sound, and one on the page for anything
// that has to answer right away (offline export, preset lists, one-shot renders). Settings go to
// both, so they always hold the same song.
package com.mobiledaw.web

import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int8Array

private external fun engCall(target: Int, f: String, args: JsArray<JsAny?>): Double
private external fun engOut(n: Int): JsAny
private external fun engLastOut(): Float32Array
private external fun engResultFloats(n: Int): Float32Array
private external fun engResultBytes(n: Int): Int8Array
private external fun engResultString(n: Int): String
private external fun engStatus(key: String): Double
private external fun engStatusArray(key: String): Float32Array?
private external fun engWant(what: String, a: Int, b: Int, c: Int)
private external fun engSampleRate(): Int

object Engine {
    const val BOTH = 0
    const val LIVE = 1
    const val PAGE = 2

    /** Marks an output array of [n] floats; read it back with [lastOut]. */
    class Out internal constructor(val js: JsAny)

    fun out(n: Int): Out = Out(engOut(n))

    fun call(target: Int, f: String, vararg args: Any?): Double {
        val arr = JsArray<JsAny?>()
        for (i in args.indices) arr[i] = toJs(args[i])
        return engCall(target, f, arr)
    }

    private fun toJs(v: Any?): JsAny? = when (v) {
        null -> null
        is Int -> v.toDouble().toJsNumber()
        is Float -> v.toDouble().toJsNumber()
        is Double -> v.toJsNumber()
        is Long -> v.toDouble().toJsNumber()
        is Boolean -> (if (v) 1.0 else 0.0).toJsNumber()
        is FloatArray -> v.toJs()
        is IntArray -> v.toJs()
        is ByteArray -> v.toJs()
        is Out -> v.js
        else -> error("Engine arg $v")
    }

    fun lastOut(into: FloatArray) = engLastOut().copyInto(into)
    fun resultFloats(n: Int): FloatArray = if (n <= 0) FloatArray(0) else engResultFloats(n).toKotlin()
    fun resultBytes(n: Int): ByteArray = if (n <= 0) ByteArray(0) else engResultBytes(n).toKotlin()
    fun resultLines(n: Int): Array<String> = if (n <= 0) emptyArray() else engResultString(n).split('\n').toTypedArray()

    fun status(key: String): Double = engStatus(key)
    fun statusArray(key: String, into: FloatArray) {
        val a = engStatusArray(key)
        if (a == null) into.fill(0f) else {
            into.fill(0f)
            a.copyInto(into)
        }
    }

    fun want(what: String, a: Int = 0, b: Int = 0, c: Int = 0) = engWant(what, a, b, c)
    val sampleRate: Int get() = engSampleRate()
}
