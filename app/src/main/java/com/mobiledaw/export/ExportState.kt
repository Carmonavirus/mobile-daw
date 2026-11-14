package com.mobiledaw.export

import java.io.File

/** Where an export is at, for the Export screen. */
sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float, val label: String) : ExportState
    /** [folder] is where the files were saved, as the user sees it (e.g. "Download/Mobile DAW/My song"). */
    /** [zip] holds every file in one .zip when stems or MIDI were exported with other files. */
    data class Done(val folder: String, val mix: List<File>, val stems: List<File>, val midi: File?, val zip: File? = null) : ExportState {
        val all: List<File> get() = mix + stems + listOfNotNull(midi)
    }
    data class Failed(val message: String) : ExportState
}
