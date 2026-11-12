package com.mobiledaw.model

import kotlinx.serialization.Serializable

/** Most mixer tracks a project can have; matches fx::kMaxInserts in app/src/main/cpp/fx/Mixer.h. */
const val MAX_MIXER_TRACKS = 8

/** Most effects on one mixer track; matches fx::kMaxSlots. */
const val MAX_EFFECTS = 8

/** Id of the "Vocals" track every project starts with; recordings play through it. */
const val VOCALS_TRACK_ID = 1L

/** Effect plugins. The id must match fx::EffectType in app/src/main/cpp/fx/Mixer.h. */
enum class EffectType(val id: Int, val label: String, val blurb: String) {
    EQ(1, "Parametric EQ", "Shape the tone with up to 8 bands"),
    PITCH(2, "Pitch Correct", "Pull the voice to the notes of a key"),
}

/** Band shapes. The id must match fx::BandType in app/src/main/cpp/fx/ParametricEq.h. */
enum class EqBandType(val id: Int, val label: String, val hasGain: Boolean) {
    BELL(0, "Bell", true),
    LOW_SHELF(1, "Low shelf", true),
    HIGH_SHELF(2, "High shelf", true),
    LOW_CUT(3, "Low cut", false),
    HIGH_CUT(4, "High cut", false),
    NOTCH(5, "Notch", false),
}

@Serializable
data class EqBand(
    val type: EqBandType = EqBandType.BELL,
    val freq: Float = 1000f,
    val gain: Float = 0f,
    val q: Float = 1f,
    /** Cuts only: 1 = 12 dB/oct, 2 = 24, 4 = 48. */
    val slope: Int = 2,
    val on: Boolean = true,
)

@Serializable
data class EqSettings(
    val bands: List<EqBand> = emptyList(),
    /** Output gain in dB. */
    val output: Float = 0f,
) {
    fun toParams(): FloatArray {
        val p = FloatArray(EQ_PARAM_COUNT)
        bands.take(EQ_BANDS).forEachIndexed { i, b ->
            val o = i * EQ_BAND_PARAMS
            p[o] = if (b.on) 1f else 0f
            p[o + 1] = b.type.id.toFloat()
            p[o + 2] = b.freq
            p[o + 3] = b.gain
            p[o + 4] = b.q
            p[o + 5] = b.slope.toFloat()
        }
        p[EQ_BANDS * EQ_BAND_PARAMS] = output
        return p
    }

    companion object {
        const val EQ_BANDS = 8
        const val EQ_BAND_PARAMS = 6
        const val EQ_PARAM_COUNT = EQ_BANDS * EQ_BAND_PARAMS + 1

        /** A gentle vocal starting point: rumble cut, a little body and air, all easy to undo. */
        fun vocalDefault() = EqSettings(
            listOf(
                EqBand(EqBandType.LOW_CUT, 80f, 0f, 0.71f, slope = 2),
                EqBand(EqBandType.BELL, 300f, 0f, 1.0f),
                EqBand(EqBandType.BELL, 3000f, 0f, 1.0f),
                EqBand(EqBandType.HIGH_SHELF, 10000f, 0f, 0.71f),
            ),
        )

        fun flat() = EqSettings(
            listOf(
                EqBand(EqBandType.LOW_SHELF, 100f, 0f, 0.71f),
                EqBand(EqBandType.BELL, 1000f, 0f, 1.0f),
                EqBand(EqBandType.HIGH_SHELF, 8000f, 0f, 0.71f),
            ),
        )
    }
}

/** Scales the pitch corrector can target. [steps] are semitones above the key. */
enum class PitchScale(val label: String, val steps: Set<Int>) {
    MAJOR("Major", setOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR("Minor", setOf(0, 2, 3, 5, 7, 8, 10)),
    CHROMATIC("Chromatic", (0..11).toSet()),
    PENTA_MAJOR("Major penta", setOf(0, 2, 4, 7, 9)),
    PENTA_MINOR("Minor penta", setOf(0, 3, 5, 7, 10)),
    BLUES("Blues", setOf(0, 3, 5, 6, 7, 10)),
}

@Serializable
data class PitchSettings(
    /** Null follows the project's key and major/minor. */
    val key: Int? = null,
    val scale: PitchScale? = null,
    /** Pitch classes (0 = C) switched off by hand on the keyboard. */
    val disabledNotes: Set<Int> = emptySet(),
    /** 0 = instant, the hard robotic effect; around 50-100 ms sounds natural. */
    val retuneMs: Float = 50f,
    val amount: Float = 1f,
    val humanize: Float = 0.2f,
    val transpose: Int = 0,
    val mix: Float = 1f,
    /** 0 low voice, 1 mid, 2 high. */
    val range: Int = 1,
) {
    fun keyFor(project: Project) = key ?: project.key
    fun scaleFor(project: Project) = scale ?: if (project.minor) PitchScale.MINOR else PitchScale.MAJOR

    /** Pitch classes the voice can land on. */
    fun allowedNotes(project: Project): Set<Int> {
        val k = keyFor(project)
        return scaleFor(project).steps.map { (it + k).mod(12) }.toSet() - disabledNotes
    }

    fun toParams(project: Project): FloatArray {
        val mask = allowedNotes(project).fold(0) { m, n -> m or (1 shl n) }
        return floatArrayOf(
            mask.toFloat(), retuneMs, amount, humanize, transpose.toFloat(), mix, range.toFloat(),
        )
    }
}

@Serializable
data class Effect(
    val id: Long,
    val type: EffectType,
    val bypass: Boolean = false,
    val eq: EqSettings? = null,
    val pitch: PitchSettings? = null,
) {
    fun params(project: Project): FloatArray = when (type) {
        EffectType.EQ -> (eq ?: EqSettings.flat()).toParams()
        EffectType.PITCH -> (pitch ?: PitchSettings()).toParams(project)
    }
}

/** A mixer track: channels and recordings routed here run through its effects into the master. */
@Serializable
data class MixerTrack(
    val id: Long,
    val name: String,
    val volume: Float = 1f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val effects: List<Effect> = emptyList(),
) {
    companion object {
        fun vocals() = MixerTrack(
            id = VOCALS_TRACK_ID,
            name = "Vocals",
            effects = listOf(
                Effect(1, EffectType.PITCH, pitch = PitchSettings()),
                Effect(2, EffectType.EQ, eq = EqSettings.vocalDefault()),
            ),
        )
    }
}
