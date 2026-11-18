package com.mobiledaw.sound

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * "Dig for samples": a random old record to sample, in the spirit of crate digging.
 *
 * Data comes from MusicBrainz (open, CC0 metadata, no key needed). A dig is three small requests:
 * a random artist with the chosen genre tag (and country), one of their albums or singles from the
 * chosen years, and a random track on it. The app never downloads audio from YouTube itself; it
 * opens a YouTube search for the track, and the user brings audio in with Import from link.
 */
object SampleDigger {
    private const val API = "https://musicbrainz.org/ws/2"
    private const val GAP_MS = 1100L // MusicBrainz asks for at most one request a second.
    private var lastCall = 0L

    class Genre(val label: String, val tag: String)
    class Era(val label: String, val from: Int, val to: Int)
    class Region(val label: String, val code: String?)

    val GENRES = listOf(
        Genre("Soul", "soul"),
        Genre("Funk", "funk"),
        Genre("Jazz", "jazz"),
        Genre("R&B", "rhythm and blues"),
        Genre("Gospel", "gospel"),
        Genre("Disco", "disco"),
        Genre("Bossa nova", "bossa nova"),
        Genre("MPB", "mpb"),
        Genre("Psych", "psychedelic rock"),
        Genre("Library", "library music"),
        Genre("Soundtrack", "soundtrack"),
        Genre("Blues", "blues"),
        Genre("Latin", "latin"),
        Genre("Afrobeat", "afrobeat"),
        Genre("Highlife", "highlife"),
        Genre("Reggae", "reggae"),
        Genre("City pop", "city pop"),
        Genre("Easy listening", "easy listening"),
        Genre("Lounge", "lounge"),
        Genre("Prog", "progressive rock"),
    )

    /** "Any" picks one of these at random, the classic hip-hop and trap sample sources. */
    private val CLASSIC = listOf("soul", "funk", "jazz", "rhythm and blues", "gospel", "bossa nova", "psychedelic rock", "library music", "soundtrack", "disco")

    val ERAS = listOf(
        Era("Any", 1955, 1989),
        Era("50s", 1950, 1959),
        Era("60s", 1960, 1969),
        Era("70s", 1970, 1979),
        Era("80s", 1980, 1989),
        Era("90s", 1990, 1999),
    )

    val REGIONS = listOf(
        Region("Anywhere", null),
        Region("USA", "US"),
        Region("UK", "GB"),
        Region("Brazil", "BR"),
        Region("France", "FR"),
        Region("Italy", "IT"),
        Region("Germany", "DE"),
        Region("Japan", "JP"),
        Region("Jamaica", "JM"),
        Region("Nigeria", "NG"),
        Region("Ghana", "GH"),
        Region("Ethiopia", "ET"),
        Region("Turkey", "TR"),
        Region("India", "IN"),
        Region("Mexico", "MX"),
        Region("Argentina", "AR"),
        Region("South Africa", "ZA"),
        Region("Korea", "KR"),
    )

    data class Dig(
        val artist: String,
        val title: String,
        val release: String,
        val year: Int?,
        val country: String?,
        val label: String?,
        val genre: String,
        val lengthMs: Int?,
        val releaseGroupId: String,
    ) {
        val query: String get() = "$artist $title"
        val youTubeUrl: String get() = "https://www.youtube.com/results?search_query=" + Http.enc(query)
        val musicBrainzUrl: String get() = "https://musicbrainz.org/release-group/$releaseGroupId"
        val discogsUrl: String get() = "https://www.discogs.com/search/?type=all&q=" + Http.enc("$artist $release")
    }

    class NothingFound : Exception("Nothing found with these filters. Try another genre, decade or country.")

    /** One random record. [genre] null means any classic sample genre. Blocking: call off the main thread. */
    suspend fun dig(genre: Genre?, era: Era, region: Region, random: Random = Random.Default): Dig {
        repeat(4) {
            val tag = genre?.tag ?: CLASSIC.random(random)
            val artist = randomArtist(tag, era, region, random) ?: return@repeat
            val group = randomReleaseGroup(artist.first, era, random) ?: return@repeat
            return pickTrack(artist.second, group, tag, random)
        }
        throw NothingFound()
    }

    /** Lucene query for an artist search (exposed for tests). */
    internal fun artistQuery(tag: String, era: Era, region: Region): String = buildString {
        append("tag:\"").append(tag).append("\"")
        // Artists who had started by the end of the era, so they have records from it.
        append(" AND begin:[1900 TO ").append(era.to).append("]")
        region.code?.let { append(" AND country:").append(it) }
    }

    private suspend fun randomArtist(tag: String, era: Era, region: Region, random: Random): Pair<String, String>? {
        val q = Http.enc(artistQuery(tag, era, region))
        val first = get("$API/artist?query=$q&limit=1&fmt=json")
        val count = (first as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull ?: 0
        if (count == 0) return null
        // Search offsets are cheap, but very deep pages are slow, so stay in the first few thousand.
        val offset = random.nextInt(minOf(count, 4000))
        val page = get("$API/artist?query=$q&limit=1&offset=$offset&fmt=json")
        val a = ((page as? JsonObject)?.get("artists") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val id = a.str("id") ?: return null
        return id to (a.str("name") ?: return null)
    }

    private suspend fun randomReleaseGroup(artistId: String, era: Era, random: Random): JsonObject? {
        val r = get("$API/release-group?artist=$artistId&type=album|single|ep&limit=100&fmt=json")
        val groups = ((r as? JsonObject)?.get("release-groups") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { yearOf(it.str("first-release-date")) in era.from..era.to }
        return groups.randomOrNull(random)
    }

    private suspend fun pickTrack(artist: String, group: JsonObject, tag: String, random: Random): Dig {
        val groupId = group.str("id")!!
        val groupTitle = group.str("title") ?: "Untitled"
        val year = yearOf(group.str("first-release-date"))
        val r = get("$API/release?release-group=$groupId&inc=recordings+labels&status=official&limit=5&fmt=json")
        val releases = ((r as? JsonObject)?.get("releases") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // The earliest pressing is usually the one people sample.
        val release = releases.minByOrNull { it.str("date") ?: "9999" }
        val tracks = (release?.get("media") as? JsonArray).orEmpty()
            .flatMap { ((it as? JsonObject)?.get("tracks") as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
        val track = tracks.randomOrNull(random)
        val label = ((release?.get("label-info") as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.let { it["label"] as? JsonObject }?.str("name")
        return Dig(
            artist = artist,
            title = track?.str("title") ?: groupTitle,
            release = groupTitle,
            year = year,
            country = release?.str("country"),
            label = label,
            genre = tag,
            lengthMs = track?.get("length")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull },
            releaseGroupId = groupId,
        )
    }

    internal fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()

    private suspend fun get(url: String): JsonElement {
        val wait = lastCall + GAP_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()
        return Http.getJson(url, mapOf("Accept" to "application/json"))
    }

    const val SAMPLETTE_URL = "https://samplette.io"

    /** Recent digs, newest first, for this app session. */
    val history = mutableListOf<Dig>()
}
