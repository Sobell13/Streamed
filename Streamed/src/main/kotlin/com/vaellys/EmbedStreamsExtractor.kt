package com.vaellys

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import java.net.URI

open class EmbedStreams : ExtractorApi() {
    override val name = "EmbedStreams"
    override val mainUrl = "https://embedsports.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (M3U8_URL_REGEX.containsMatchIn(url)) {
            val playbackReferer = referer ?: mainUrl
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = url,
                referer = playbackReferer,
                headers = playbackHeaders(playbackReferer),
            ).forEach(callback)
            return
        }

        val resolver = WebViewResolver(
            interceptUrl = M3U8_URL_REGEX,
            useOkhttp = false,
            script = PLAY_SCRIPT,
            timeout = WEBVIEW_TIMEOUT_MS,
        )

        val (manifestRequest, _) = resolver.resolveUsingWebView(
            url = url,
            referer = referer,
        )

        val request = manifestRequest ?: return
        val manifestUrl = request.url.toString()
        val headers = request.headers.toMap().toMutableMap().apply {
            putHeaderIfAbsent("User-Agent", USER_AGENT)
            putHeaderIfAbsent("Referer", url)
            originOf(url)?.let { putHeaderIfAbsent("Origin", it) }
        }

        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = manifestUrl,
            referer = url,
            headers = headers,
        ).forEach(callback)
    }

    private fun playbackHeaders(url: String): Map<String, String> {
        return buildMap {
            put("User-Agent", USER_AGENT)
            put("Referer", url)
            originOf(url)?.let { put("Origin", it) }
        }
    }

    private fun MutableMap<String, String>.putHeaderIfAbsent(
        name: String,
        value: String,
    ) {
        if (keys.none { it.equals(name, ignoreCase = true) }) {
            put(name, value)
        }
    }

    private fun originOf(url: String): String? {
        return runCatching {
            val uri = URI(url)
            val scheme = uri.scheme ?: return@runCatching null
            val host = uri.host ?: return@runCatching null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            "$scheme://$host$port"
        }.getOrNull()
    }

    private companion object {
        const val WEBVIEW_TIMEOUT_MS = 15_000L
        val M3U8_URL_REGEX = Regex("(?i)\\.m3u8(?:[?#].*)?$")

        val PLAY_SCRIPT = """
            setTimeout(function () {
                try {
                    const playButton = document.querySelector('.jw-icon-display');
                    if (playButton) {
                        playButton.click();
                    } else if (typeof jwplayer !== 'undefined') {
                        jwplayer().play();
                    }
                } catch (_) {}
            }, 1500);
        """.trimIndent()
    }
}

class EmbedSporty : EmbedStreams() {
    override val name = "EmbedSporty"
    override val mainUrl = "https://embed.st"
}
