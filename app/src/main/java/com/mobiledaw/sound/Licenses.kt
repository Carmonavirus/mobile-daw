package com.mobiledaw.sound

import kotlinx.serialization.Serializable

/** How a downloaded sound may be used, simplified from its Creative Commons (or other) licence. */
enum class LicenseKind(val label: String, val note: String) {
    CC0("CC0", "Free for anything, no credit needed."),
    PUBLIC_DOMAIN("Public domain", "Free for anything, no credit needed."),
    BY("CC BY", "Free to use, also in released music. Credit the author."),
    BY_SA("CC BY-SA", "Credit the author, and music using it must be shared under the same licence."),
    NC("Non-commercial", "Credit the author. Don't use it in music you sell or monetise."),
    OTHER("Other", "Check the licence on the sound's page before releasing music with it."),
    ;

    /** True when using the sound in a released beat needs nothing but (at most) a credit. */
    val releaseFriendly: Boolean get() = this == CC0 || this == PUBLIC_DOMAIN || this == BY

    companion object {
        /** Reads licence names and URLs as Freesound, Wikimedia and the Internet Archive give them. */
        fun from(text: String?): LicenseKind {
            val t = text.orEmpty().lowercase()
            return when {
                t.isBlank() -> OTHER
                "zero" in t || t == "cc0" || "creative commons 0" in t || "cc0" in t -> CC0
                "publicdomain/mark" in t || "public domain" in t || t == "pd" || t.startsWith("pd-") -> PUBLIC_DOMAIN
                "nc" in t.split('-', ' ', '/', '_') || "noncommercial" in t.replace(" ", "") || "by-nc" in t -> NC
                "sa" in t.split('-', ' ', '/', '_') || "by-sa" in t || "sharealike" in t.replace(" ", "") -> BY_SA
                "sampling+" in t || "nd" in t.split('-', ' ', '/', '_') -> OTHER
                "attribution" in t || "/by/" in t || t.startsWith("cc by") || t.startsWith("cc-by") -> BY
                else -> OTHER
            }
        }
    }
}

/** Which licences a search may return. */
enum class LicenseFilter(val label: String, val kinds: Set<LicenseKind>) {
    NO_CREDIT("No credit needed", setOf(LicenseKind.CC0, LicenseKind.PUBLIC_DOMAIN)),
    RELEASE("OK for releases", setOf(LicenseKind.CC0, LicenseKind.PUBLIC_DOMAIN, LicenseKind.BY)),
    ALL("All licences", LicenseKind.entries.toSet()),
}

enum class OnlineSource(val label: String, val site: String) {
    FREESOUND("Freesound", "freesound.org"),
    COMMONS("Wikimedia Commons", "commons.wikimedia.org"),
    ARCHIVE("Internet Archive", "archive.org"),
}

/** A sound found online, before it is downloaded. */
data class OnlineSound(
    val source: OnlineSource,
    val id: String,
    val title: String,
    val author: String,
    val license: LicenseKind,
    val licenseName: String,
    val licenseUrl: String?,
    val pageUrl: String,
    val durationSeconds: Double?,
    /** Short, compressed preview that needs no sign-in. */
    val previewUrl: String,
    /** The original file, when it can be fetched directly. */
    val fileUrl: String?,
)

/** Who made a sound saved from an online library, and under what licence. */
@Serializable
data class Credit(
    val path: String,
    val title: String,
    val author: String,
    val source: String,
    val license: String,
    val licenseUrl: String? = null,
    val pageUrl: String,
) {
    val kind: LicenseKind get() = LicenseKind.from(license)

    /** One line suitable for a track description. */
    fun line(): String = "\"$title\" by $author ($source, $license) $pageUrl"
}
