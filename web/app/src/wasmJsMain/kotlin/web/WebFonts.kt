package com.mobiledaw.web

import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface

/** The app's text font on the web (browsers give Compose no system fonts), loaded at start. */
object WebFonts {
    var regular: ByteArray? = null
    var bold: ByteArray? = null
    private val faces = HashMap<Boolean, Typeface?>()

    fun typeface(bold: Boolean): Typeface? = faces.getOrPut(bold) {
        (if (bold) this.bold ?: regular else regular)?.let { FontMgr.default.makeFromData(Data.makeFromBytes(it)) }
    }
}
