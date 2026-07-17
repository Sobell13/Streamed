package com.vaellys

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

class Streamed : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "Streamed"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/api/matches/live/popular" to "Live Popular",
        "$mainUrl/api/matches/live" to "Live",
        "$mainUrl/api/matches/all-today/popular" to "Today's Popular Matches",
        "$mainUrl/api/matches/football/popular" to "Football",
        "$mainUrl/api/matches/mma/popular" to "MMA",
        "$mainUrl/api/matches/boxing/popular" to "Boxing",
        "$mainUrl/api/matches/american-football/popular" to "American Football",
        "$mainUrl/api/matches/basketball/popular" to "Basketball",
        "$mainUrl/api/matches/tennis/popular" to "Tennis",
        "$mainUrl/api/matches/hockey/popular" to "Hockey",
        "$mainUrl/api/matches/baseball/popular" to "Baseball",
        "$mainUrl/api/matches/rugby/popular" to "Rugby",
        "$mainUrl/api/matches/darts/popular" to "Darts",
        "$mainUrl/api/matches/motor-sports/popular" to "Motor Sports",
        "$mainUrl/api/matches/golf/popular" to "Golf",
        "$mainUrl/api/matches/billiards/popular" to "Billiards",
        "$mainUrl/api/matches/afl/popular" to "AFL",
        "$mainUrl/api/matches/cricket/popular" to "Cricket",
        "$mainUrl/api/matches/other/popular" to "Other",
    )

    private val mapper = jacksonObjectMapper()
    private val genericEmbedExtractor = EmbedStreams()
    private val matchCache = mutableMapOf<String, MatchCacheEntry>()
    private val matchCacheMutex = Mutex()
    private val streamRequestSemaphore = Semaphore(MAX_CONCURRENT_STREAM_REQUESTS)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val matches = getMatchesSafe(request.data)
        val items = matches
            .asSequence()
            .filter { it.sources.isNotEmpty() }
            .distinctBy { it.id }
            .map { it.toSearchResponse() }
            .toList()

        return newHomePageResponse(request, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        return getMatchesSafe("$mainUrl/api/matches/all")
            .asSequence()
            .filter { it.sources.isNotEmpty() }
            .distinctBy { it.id }
            .filter { normalize(it.title).contains(normalizedQuery) }
            .sortedWith(
                compareByDescending<ApiMatch> {
                    normalize(it.title).startsWith(normalizedQuery)
                }.thenBy {
                    normalize(it.title)
                }
            )
            .map { it.toSearchResponse() }
            .toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val matchId = decodeMatchId(url) ?: return null
        val match = findMatch(matchId) ?: return null

        val loadData = mapper.writeValueAsString(
            StreamLoadData(
                matchId = match.id,
                sources = match.sources,
            )
        )

        return newLiveStreamLoadResponse(
            name = match.title,
            url = url,
            dataUrl = loadData,
        ) {
            val resolvedPoster = resolvePoster(match)
            posterUrl = resolvedPoster
            posterHeaders = resolvedPoster?.let { POSTER_HEADERS }
            plot = buildDescription(match)
            tags = listOf(match.category)
            comingSoon = match.sources.isEmpty()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val loadData = resolveLoadData(data) ?: return false
        if (loadData.sources.isEmpty()) return false

        val streams = fetchStreams(loadData.sources)
            .filter { it.embedUrl.isNotBlank() }
            .distinctBy { it.embedUrl }

        var emitted = false

        for (stream in streams) {
            val extractedLinks = mutableListOf<ExtractorLink>()

            try {
                val extractorMatched = loadExtractor(
                    url = stream.embedUrl,
                    referer = mainUrl,
                    subtitleCallback = subtitleCallback,
                    callback = extractedLinks::add,
                )

                // Streamed's embedUrl is normally an iframe/player page, not the HLS URL.
                // loadExtractor only runs when the URL matches a registered extractor domain,
                // so use the generic WebView resolver when no extractor emitted a link.
                if (extractedLinks.isEmpty() && !M3U8_URL_REGEX.containsMatchIn(stream.embedUrl)) {
                    genericEmbedExtractor.getUrl(
                        url = stream.embedUrl,
                        referer = mainUrl,
                        subtitleCallback = subtitleCallback,
                        callback = extractedLinks::add,
                    )
                }

                Log.d(
                    TAG,
                    "embed=${stream.embedUrl}, extractorMatched=$extractorMatched, links=${extractedLinks.size}",
                )

                if (extractedLinks.isEmpty() && M3U8_URL_REGEX.containsMatchIn(stream.embedUrl)) {
                    extractedLinks += M3u8Helper.generateM3u8(
                        source = stream.source,
                        streamUrl = stream.embedUrl,
                        referer = mainUrl,
                        name = streamLabel(stream),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to extract ${stream.embedUrl}", error)
            }

            for (link in extractedLinks) {
                callback(decorateLink(stream, link))
                emitted = true
            }
        }

        return emitted
    }

    private suspend fun getMatchesSafe(endpoint: String): List<ApiMatch> {
        return try {
            getMatches(endpoint)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Failed to load matches from $endpoint", error)
            emptyList()
        }
    }

    private suspend fun getMatches(endpoint: String): List<ApiMatch> = matchCacheMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = matchCache[endpoint]

        if (cached != null && now - cached.createdAt < MATCH_CACHE_MS) {
            return@withLock cached.matches
        }

        val matches: List<ApiMatch> = mapper.readValue(app.get(endpoint).text)
        matchCache[endpoint] = MatchCacheEntry(now, matches)
        matches
    }

    private suspend fun resolveLoadData(data: String): StreamLoadData? {
        try {
            return mapper.readValue<StreamLoadData>(data)
        } catch (_: Exception) {
            // Backward compatibility for entries saved by older versions, whose data was a URL.
        }

        val matchId = decodeMatchId(data) ?: return null
        val match = findMatch(matchId) ?: return null

        return StreamLoadData(match.id, match.sources)
    }

    private suspend fun findMatch(identifier: String): ApiMatch? {
        return getMatchesSafe("$mainUrl/api/matches/all").firstOrNull { match ->
            match.id == identifier || match.sources.any { it.id == identifier }
        }
    }

    private suspend fun fetchStreams(sources: List<Source>): List<ApiStream> = coroutineScope {
        sources
            .distinctBy { it.source to it.id }
            .map { source ->
                async {
                    streamRequestSemaphore.withPermit {
                        try {
                            val endpoint = "$mainUrl/api/stream/${source.source}/${source.id}"
                            mapper.readValue<List<ApiStream>>(app.get(endpoint).text)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.e(
                                TAG,
                                "Failed stream source ${source.source}/${source.id}",
                                error,
                            )
                            emptyList()
                        }
                    }
                }
            }
            .awaitAll()
            .flatten()
    }

    private suspend fun decorateLink(stream: ApiStream, link: ExtractorLink): ExtractorLink {
        // Preserve specialized links exactly as returned by their extractor.
        if (link is DrmExtractorLink || link is ExtractorLinkPlayList) return link

        val label = streamLabel(stream)
        val originalName = link.name.takeIf {
            it.isNotBlank() && !it.equals(link.source, ignoreCase = true)
        }
        val displayName = listOfNotNull(label, originalName).joinToString(" • ")

        return newExtractorLink(
            source = link.source,
            name = displayName,
            url = link.url,
            type = link.type,
        ) {
            referer = link.referer
            quality = link.quality
            headers = link.headers
            extractorData = link.extractorData
            audioTracks = link.audioTracks
        }
    }

    private fun streamLabel(stream: ApiStream): String {
        val source = stream.source.ifBlank { "Streamed" }
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val language = stream.language.ifBlank { "Unknown language" }
        val quality = if (stream.hd) "HD" else "SD"
        return "$source #${stream.streamNo} • $language • $quality"
    }

    private fun ApiMatch.toSearchResponse(): SearchResponse {
        val resolvedPoster = resolvePoster(this)
        return newLiveSearchResponse(
            name = title,
            url = matchUrl(id),
            type = TvType.Live,
        ) {
            posterUrl = resolvedPoster
            posterHeaders = resolvedPoster?.let { POSTER_HEADERS }
        }
    }

    private fun resolvePoster(match: ApiMatch): String? {
        val poster = match.poster?.trim()?.takeIf { it.isNotEmpty() }

        if (poster != null) {
            return when {
                poster.startsWith("https://", ignoreCase = true) ||
                    poster.startsWith("http://", ignoreCase = true) -> poster

                poster.startsWith("/") -> ensureWebp("$mainUrl$poster")
                poster.startsWith("api/") -> ensureWebp("$mainUrl/$poster")
                else -> "$mainUrl/api/images/proxy/${poster.removeSuffix(".webp")}.webp"
            }
        }

        val homeBadge = badgeId(match.teams?.home?.badge)
        val awayBadge = badgeId(match.teams?.away?.badge)

        return when {
            homeBadge != null && awayBadge != null ->
                "$mainUrl/api/images/poster/$homeBadge/$awayBadge.webp"

            homeBadge != null -> "$mainUrl/api/images/badge/$homeBadge.webp"
            awayBadge != null -> "$mainUrl/api/images/badge/$awayBadge.webp"
            else -> null
        }
    }

    private fun badgeId(value: String?): String? {
        return value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.substringBefore('?')
            ?.substringAfterLast('/')
            ?.removeSuffix(".webp")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun ensureWebp(url: String): String {
        val path = url.substringBefore('?')
        val lastSegment = path.substringAfterLast('/')
        return if (lastSegment.contains('.')) url else "$url.webp"
    }

    private fun buildDescription(match: ApiMatch): String {
        val now = System.currentTimeMillis()
        val timing = if (match.date > now) {
            val remainingMinutes = (match.date - now) / 60_000L
            val days = remainingMinutes / (24L * 60L)
            val hours = (remainingMinutes % (24L * 60L)) / 60L
            val minutes = remainingMinutes % 60L

            when {
                days > 0 -> "Starts in ${days}d ${hours}h."
                hours > 0 -> "Starts in ${hours}h ${minutes}m."
                minutes > 0 -> "Starts in ${minutes}m."
                else -> "Starting shortly."
            }
        } else {
            "The scheduled start time has passed; the event may be live or completed."
        }

        val sourceText = when (match.sources.size) {
            0 -> "No stream sources are currently listed."
            1 -> "1 stream source is currently listed."
            else -> "${match.sources.size} stream sources are currently listed."
        }

        return "$timing\n\n$sourceText"
    }

    private fun normalize(value: String): String {
        val lowercase = value.lowercase(Locale.ROOT)
            .replace('ı', 'i')
            .replace('ç', 'c')
            .replace('ğ', 'g')
            .replace('ö', 'o')
            .replace('ş', 's')
            .replace('ü', 'u')

        return Normalizer.normalize(lowercase, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .replace(MULTIPLE_SPACES_REGEX, " ")
            .trim()
    }

    private fun matchUrl(id: String): String {
        val encodedId = URLEncoder.encode(id, Charsets.UTF_8.name())
        return "$mainUrl/watch/$encodedId"
    }

    private fun decodeMatchId(url: String): String? {
        val encoded = url.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            URLDecoder.decode(encoded, Charsets.UTF_8.name())
        }.getOrNull()
    }

    private data class MatchCacheEntry(
        val createdAt: Long,
        val matches: List<ApiMatch>,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class StreamLoadData(
        @JsonProperty("matchId") val matchId: String,
        @JsonProperty("sources") val sources: List<Source> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiMatch(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("category") val category: String,
        @JsonProperty("date") val date: Long,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("popular") val popular: Boolean = false,
        @JsonProperty("teams") val teams: Teams? = null,
        @JsonProperty("sources") val sources: List<Source> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Source(
        @JsonProperty("source") val source: String,
        @JsonProperty("id") val id: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Teams(
        @JsonProperty("home") val home: Team? = null,
        @JsonProperty("away") val away: Team? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Team(
        @JsonProperty("name") val name: String,
        @JsonProperty("badge") val badge: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiStream(
        @JsonProperty("id") val id: String,
        @JsonProperty("streamNo") val streamNo: Int,
        @JsonProperty("language") val language: String,
        @JsonProperty("hd") val hd: Boolean = false,
        @JsonProperty("embedUrl") val embedUrl: String,
        @JsonProperty("source") val source: String,
    )

    private companion object {
        const val TAG = "Streamed"
        const val MATCH_CACHE_MS = 30_000L
        const val MAX_CONCURRENT_STREAM_REQUESTS = 4

        val POSTER_HEADERS = mapOf("User-Agent" to USER_AGENT)
        val COMBINING_MARKS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
        val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]+")
        val MULTIPLE_SPACES_REGEX = Regex("\\s+")
        val M3U8_URL_REGEX = Regex("(?i)\\.m3u8(?:[?#].*)?$")
    }
}
