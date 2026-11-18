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
            if (clip.muted) continue
            val pattern = project.pattern(clip.patternId) ?: continue
            // Only the part of the pattern the clip shows plays: notes from `trim` to `trim + length`,
            // shifted so `trim` lands on the clip's start and cut off at the clip's end.
            val from = clip.trim
            val to = minOf(pattern.lengthTicks, from + project.clipLength(clip))
            val offset = clip.startTick - from
            project.channels.forEachIndexed { index, ch ->
                pattern.notesFor(ch.id).forEach { n ->
                    if (n.start in from until to) add(out, index, offset + n.start, n, to - n.start)
                }
            }
        }
        // Audio clips play on hidden engine slots after the channels (see DawViewModel.syncEngine).
        audioSlots(project).forEach { (slot, clip) ->
            if (clip.muted) return@forEach
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
