package com.mobiledaw.model

/** Flattens a project into the note list the native engine plays. */
object SequenceCompiler {
    class Compiled(val events: IntArray, val loopTicks: Int)

    fun pattern(project: Project, pattern: Pattern): Compiled {
        val out = ArrayList<Int>()
        project.channels.forEachIndexed { index, ch ->
            pattern.notesFor(ch.id).forEach { n ->
                if (n.start < pattern.lengthTicks) add(out, index, n.start, n, pattern.lengthTicks)
            }
        }
        return Compiled(out.toIntArray(), pattern.lengthTicks)
    }

    fun song(project: Project): Compiled {
        val out = ArrayList<Int>()
        for (clip in project.clips) {
            val pattern = project.pattern(clip.patternId) ?: continue
            val offset = clip.bar * TICKS_PER_BAR
            project.channels.forEachIndexed { index, ch ->
                pattern.notesFor(ch.id).forEach { n ->
                    if (n.start < pattern.lengthTicks) add(out, index, offset + n.start, n, pattern.lengthTicks - n.start)
                }
            }
        }
        // Audio clips play on hidden engine slots after the channels (see DawViewModel.syncEngine).
        audioSlots(project).forEach { (slot, clip) ->
            out += slot
            out += clip.startTick
            out += clip.lengthTicks(project.bpm)
            out += 60
            out += 127
        }
        return Compiled(out.toIntArray(), project.songBars * TICKS_PER_BAR)
    }

    /** Engine slot for each audio clip that fits after the channels (32 slots in total). */
    fun audioSlots(project: Project, maxSlots: Int = 32): List<Pair<Int, AudioClip>> =
        project.audioClips.mapIndexedNotNull { i, clip ->
            val slot = project.channels.size + i
            if (slot < maxSlots) slot to clip else null
        }

    private fun add(out: MutableList<Int>, channel: Int, start: Int, n: Note, maxLength: Int) {
        out += channel
        out += start
        out += n.length.coerceIn(1, maxOf(1, maxLength))
        out += n.pitch
        out += n.velocity.coerceIn(1, 127)
    }
}
