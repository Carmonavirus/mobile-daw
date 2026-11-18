// Web version of app/src/main/java/com/mobiledaw/audio/SynthEngine.kt (see web/engine/bridge.cpp).
@file:Suppress("PackageDirectoryMismatch")

package com.mobiledaw.audio

import com.mobiledaw.web.Engine

object SynthEngine {
    fun paramNames(): Array<String> = Engine.resultLines(Engine.call(Engine.PAGE, "se_paramNames").toInt())
    fun paramRanges(): FloatArray = Engine.resultFloats(Engine.call(Engine.PAGE, "se_paramRanges").toInt())
    fun tableNames(): Array<String> = Engine.resultLines(Engine.call(Engine.PAGE, "se_tableNames").toInt())
    fun tableShape(table: Int, position: Float, out: FloatArray) {
        Engine.call(Engine.PAGE, "se_tableShape", table, position, Engine.out(out.size))
        Engine.lastOut(out)
    }
    fun setParam(channel: Int, param: Int, value: Float) { Engine.call(Engine.BOTH, "se_setParam", channel, param, value) }
    fun setParams(channel: Int, values: FloatArray) { Engine.call(Engine.BOTH, "se_setParams", channel, values) }
    fun noteHold(channel: Int, pitch: Int, velocity: Int, on: Boolean) { Engine.call(Engine.LIVE, "se_noteHold", channel, pitch, velocity, on) }
}
