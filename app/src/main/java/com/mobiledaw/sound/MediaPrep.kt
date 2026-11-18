package com.mobiledaw.sound

import java.io.File

/**
 * Gets a downloaded media file ready for the app's import code. On the phone there is nothing to
 * do (Android decodes straight from the file); the web app's twin has the browser decode it first.
 */
object MediaPrep {
    suspend fun prepare(file: File) {}
}
