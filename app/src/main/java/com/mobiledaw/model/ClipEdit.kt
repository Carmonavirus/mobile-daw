package com.mobiledaw.model

/**
 * Pure editing math for playlist clips: cutting in two and dragging the left / right edge.
 * Times are playlist ticks. Audio clips keep their edges inside the source file, so they can
 * be made shorter and longer again, but never longer than the recording itself.
 */
object ClipEdit {
    /** Shortest a clip can get. */
    const val MIN_TICKS = TICKS_PER_STEP
    private const val MIN_AUDIO_SECONDS = 0.02f

    fun ticksToSeconds(ticks: Int, bpm: Int): Float = ticks * 60f / (bpm.coerceAtLeast(1) * TICKS_PER_BEAT)

    // ---------------------------------------------------------------- pattern clips

    /** Cuts a pattern clip at [tick] (absolute). Null when the cut would leave an empty piece. */
    fun split(project: Project, clip: Clip, tick: Int): Pair<Clip, Clip>? {
        val len = project.clipLength(clip)
        val at = tick - clip.startTick
        if (at < MIN_TICKS || at > len - MIN_TICKS) return null
        val left = clip.copy(trim = clip.trim, length = at)
        val right = clip.movedTo(clip.lane, clip.startTick + at).copy(trim = clip.trim + at, length = len - at)
        return left to right
    }

    /** Moves the left edge to [tick]; the content stays where it is (the start of the pattern is hidden or shown). */
    fun resizeLeft(project: Project, clip: Clip, tick: Int): Clip {
        val len = project.clipLength(clip)
        val end = clip.startTick + len
        // Can't show more than the pattern's start (trim >= 0), or go shorter than the minimum.
        val earliest = clip.startTick - clip.trim
        val start = tick.coerceIn(maxOf(0, earliest), end - MIN_TICKS)
        val delta = start - clip.startTick
        return clip.movedTo(clip.lane, start).copy(trim = clip.trim + delta, length = len - delta)
    }

    /** Moves the right edge to [tick], up to the end of the pattern. */
    fun resizeRight(project: Project, clip: Clip, tick: Int): Clip {
        val patternTicks = project.pattern(clip.patternId)?.lengthTicks ?: TICKS_PER_BAR
        val maxLen = (patternTicks - clip.trim).coerceAtLeast(MIN_TICKS)
        val len = (tick - clip.startTick).coerceIn(MIN_TICKS, maxLen)
        return clip.copy(length = len)
    }

    // ---------------------------------------------------------------- audio clips

    /** Seconds of the source file that play in [ticks] of the timeline. */
    private fun sourceSeconds(clip: AudioClip, ticks: Int, bpm: Int): Float = ticksToSeconds(ticks, bpm) * clip.rate

    /** Cuts an audio clip at [tick] (absolute). The second piece gets [newId]. */
    fun split(clip: AudioClip, tick: Int, bpm: Int, newId: Long): Pair<AudioClip, AudioClip>? {
        val at = tick - clip.startTick
        if (at < MIN_TICKS || at > clip.lengthTicks(bpm) - MIN_TICKS) return null
        val d = sourceSeconds(clip, at, bpm).coerceIn(MIN_AUDIO_SECONDS, clip.seconds - MIN_AUDIO_SECONDS)
        val src = clip.sourceSeconds
        // Reversed clips play the file from its end, so the first piece on screen is the end of the file.
        val left = if (!clip.reverse) clip.copy(seconds = d, fileSeconds = src)
        else clip.copy(offset = clip.offset + clip.seconds - d, seconds = d, fileSeconds = src)
        val right = if (!clip.reverse) clip.copy(id = newId, startTick = tick, offset = clip.offset + d, seconds = clip.seconds - d, fileSeconds = src)
        else clip.copy(id = newId, startTick = tick, seconds = clip.seconds - d, fileSeconds = src)
        return left to right
    }

    /** Moves the left edge to [tick], showing more or less of the file but never past its start/end. */
    fun resizeLeft(clip: AudioClip, tick: Int, bpm: Int): AudioClip {
        val src = clip.sourceSeconds
        // Seconds of file that can still be revealed on the left.
        val room = if (!clip.reverse) clip.offset else src - clip.offset - clip.seconds
        val end = clip.endTick(bpm)
        val wanted = tick.coerceAtMost(end - MIN_TICKS).coerceAtLeast(0)
        var d = sourceSeconds(clip, wanted - clip.startTick, bpm) // > 0 shortens, < 0 lengthens
        d = d.coerceIn(-room.coerceAtLeast(0f), clip.seconds - MIN_AUDIO_SECONDS)
        val seconds = clip.seconds - d
        val offset = if (!clip.reverse) clip.offset + d else clip.offset
        // Keep the right edge where it was: the new start is the old end minus the new length.
        val moved = clip.copy(offset = offset, seconds = seconds, fileSeconds = src)
        val start = (end - moved.lengthTicks(bpm)).coerceAtLeast(0)
        return moved.copy(startTick = start)
    }

    /** Moves the right edge to [tick], up to the end of the file (start, when reversed). */
    fun resizeRight(clip: AudioClip, tick: Int, bpm: Int): AudioClip {
        val src = clip.sourceSeconds
        val room = if (!clip.reverse) src - clip.offset - clip.seconds else clip.offset
        val wantedTicks = (tick - clip.startTick).coerceAtLeast(MIN_TICKS)
        val e = (sourceSeconds(clip, wantedTicks, bpm) - clip.seconds)
            .coerceIn(-(clip.seconds - MIN_AUDIO_SECONDS), room.coerceAtLeast(0f))
        val seconds = clip.seconds + e
        val offset = if (!clip.reverse) clip.offset else clip.offset - e
        return clip.copy(offset = offset.coerceAtLeast(0f), seconds = seconds, fileSeconds = src)
    }

    /** Engine trim points for the used part of the file, as fractions 0..1. */
    fun trimFractions(clip: AudioClip): Pair<Float, Float> {
        val src = clip.sourceSeconds
        if (src <= 0f) return 0f to 1f
        val a = (clip.offset / src).coerceIn(0f, 1f)
        val b = ((clip.offset + clip.seconds) / src).coerceIn(a, 1f)
        return a to b
    }
}
