package com.mobiledaw.model

/**
 * The project a fresh install and "New project" open with: empty. No sounds, no notes and no
 * playlist clips, just one blank pattern to start from (Add drums / Add sounds fill the rack).
 */
object DemoProject {
    fun create(name: String = "New project"): Project = empty(name)

    fun empty(name: String): Project = Project(
        name = name,
        channels = emptyList(),
        patterns = listOf(Pattern(id = 1, name = "Pattern 1")),
        clips = emptyList(),
    )
}
