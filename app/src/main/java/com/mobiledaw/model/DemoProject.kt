package com.mobiledaw.model

/** The project a fresh install opens with: a short beat so there is something to hear right away. */
object DemoProject {
    fun create(name: String = "First beat"): Project {
        val channels = listOf(
            Channel(1, "Kick", Instrument.KICK, volume = 0.9f),
            Channel(2, "Snare", Instrument.SNARE, volume = 0.75f),
            Channel(3, "Hat", Instrument.HAT, volume = 0.6f, pan = 0.2f),
            Channel(4, "Clap", Instrument.CLAP, volume = 0.6f, pan = -0.15f),
            Channel(5, "Bass", Instrument.BASS, volume = 0.8f),
            Channel(6, "Lead", Instrument.LEAD, volume = 0.55f),
        )

        fun steps(vararg s: Int, pitch: Int = 60) = s.map { Note(it * TICKS_PER_STEP, TICKS_PER_STEP, pitch) }

        val drums = Pattern(
            id = 7,
            name = "Drums",
            bars = 1,
            notes = mapOf(
                1L to steps(0, 7, 10),
                2L to steps(4, 12),
                3L to steps(0, 2, 4, 6, 8, 10, 12, 14, 15),
                4L to steps(12),
            ),
        )

        // C minor: bass on C / Ab / Eb / Bb, a simple lead line on top.
        val bassLine = listOf(36, 32, 39, 34).flatMapIndexed { bar, root ->
            listOf(
                Note(bar * TICKS_PER_BAR, TICKS_PER_STEP * 6, root),
                Note(bar * TICKS_PER_BAR + TICKS_PER_STEP * 8, TICKS_PER_STEP * 4, root),
                Note(bar * TICKS_PER_BAR + TICKS_PER_STEP * 14, TICKS_PER_STEP * 2, root + 12),
            )
        }
        val leadLine = listOf(
            0 to 72, 6 to 75, 12 to 79,
            16 to 77, 22 to 75, 28 to 72,
            32 to 75, 38 to 79, 44 to 82,
            48 to 79, 54 to 77, 60 to 74,
        ).map { (step, pitch) -> Note(step * TICKS_PER_STEP, TICKS_PER_STEP * 5, pitch) }

        val melody = Pattern(
            id = 8,
            name = "Melody",
            bars = 4,
            notes = mapOf(5L to bassLine, 6L to leadLine),
        )

        val clips = (0 until 8).map { Clip(lane = 0, bar = it, patternId = 7) } +
            listOf(Clip(lane = 1, bar = 0, patternId = 8), Clip(lane = 1, bar = 4, patternId = 8))

        return Project(name = name, bpm = 140, key = 0, minor = true, channels = channels, patterns = listOf(drums, melody), clips = clips)
    }

    fun empty(name: String): Project = Project(
        name = name,
        channels = listOf(
            Channel(1, "Kick", Instrument.KICK, volume = 0.9f),
            Channel(2, "Snare", Instrument.SNARE, volume = 0.75f),
            Channel(3, "Hat", Instrument.HAT, volume = 0.6f),
            Channel(4, "Lead", Instrument.LEAD, volume = 0.55f),
        ),
        patterns = listOf(Pattern(id = 5, name = "Pattern 1")),
        clips = emptyList(),
    )
}
