@file:Suppress("unused", "PackageDirectoryMismatch")

package android.graphics

/** Text paint for the few labels drawn with nativeCanvas.drawText (see web/TextDraw.kt). */
class Paint {
    var color: Int = 0xff000000.toInt()
    var textSize: Float = 12f
    var isAntiAlias: Boolean = true
    var isFakeBoldText: Boolean = false
    var textAlign: Align = Align.LEFT
    var typeface: Any? = null
    enum class Align { LEFT, CENTER, RIGHT }
}
