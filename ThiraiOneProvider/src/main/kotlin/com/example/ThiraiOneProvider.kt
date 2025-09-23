package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URL

class ThiraiOneProvider : MainAPI() {
    override var mainUrl = "https://tamildhool.tech"
    override var name = "ThiraiOne"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ta"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/vijay-tv/vijay-tv-serial/" to "Vijay TV Serials",
        "$mainUrl/vijay-tv/vijay-tv-show/" to "Vijay TV Shows",
        "$mainUrl/sun-tv/sun-tv-serial/" to "Sun TV Serials",
        "$mainUrl/sun-tv/sun-tv-show/" to "Sun TV Shows",
        "$mainUrl/zee-tamil/zee-tamil-serial/" to "Zee Tamil Serials",
        "$mainUrl/zee-tamil/zee-tamil-show/" to "Zee Tamil Shows",
        "$mainUrl/kalaignar-tv/" to "Kalaignar TV"
    )

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("(?i)\\s*(Vijay\\s*Tv\\s*(Serial|Show)|Zee\\s*Tamil\\s*(Serial|Show)|Sun\\s*Tv\\s*(Serial|Show)|Kalaignar\\s*Tv\\s*(Serial|Show)?|\\|\\s*On\\s*Kalaignar\\s*TV)\\s*"), "")
            .replace(Regex("(?i)\\s*-\\s*(Vijay|Zee|Sun|Kalaignar)\\s*(TV|Tamil)\\s*"), "")
            .replace(Regex("(?i)\\s*\\|\\s*(Tamil|Serial|Show)\\s*"), "")
            .trim()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + "page/$page/").document
        val episodes = doc.select("article.regular-post").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, episodes, true)),
            hasNext = doc.selectFirst(".navigation .next") != null
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawTitle = this.selectFirst("h3.entry-title a")?.text() ?: return null
        val title = cleanTitle(rawTitle)
        val href = this.selectFirst("h3.entry-title a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst(".post-thumb img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+") }"
        val doc = app.get(searchUrl).document
        return doc.select("article.regular-post").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("h1.entry-title")?.text() ?: return null
        val title = cleanTitle(rawTitle)
        val description = doc.select("h2.wp-block-heading:has(span#Plot) + p").firstOrNull()?.text()
        val posterUrl = doc.selectFirst(".entry-cover")?.attr("style")?.let {
            Regex("background-image:url\\('(.*?)'\\)").find(it)?.groupValues?.get(1)
        } ?: doc.selectFirst("img")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.TvSeries, url) {
            this.plot = description
            this.posterUrl = posterUrl
        }
    }

    private suspend fun followRedirectChain(
        initialUrl: String,
        initialReferer: String,
        maxRedirects: Int = 5
    ): String? {
        var currentUrl = initialUrl
        var currentReferer = initialReferer

        for (i in 0 until maxRedirects) {
            try {
                val response = app.get(
                    currentUrl,
                    headers = mapOf(
                        "Referer" to currentReferer,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
                    ),
                    allowRedirects = false
                )

                val location = response.headers["location"]?.firstOrNull()
                if (location != null) {
                    currentReferer = currentUrl
                    currentUrl = if (location.startsWith("http")) {
                        location
                    } else {
                        val baseUrl = URL(currentUrl)
                        if (location.startsWith("/")) {
                            "${baseUrl.protocol}://${baseUrl.host}$location"
                        } else {
                            "${baseUrl.protocol}://${baseUrl.host}/$location"
                        }
                    }
                    continue
                }

                if (currentUrl.contains("thrfive.io/embed/")) {
                    return currentUrl
                }

                val doc = response.document

                val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                if (metaRefresh != null) {
                    val urlMatch = Regex("url=(.+)", RegexOption.IGNORE_CASE).find(metaRefresh)
                    if (urlMatch != null) {
                        currentReferer = currentUrl
                        val newUrl = urlMatch.groups[1]?.value
                        if (newUrl != null) {
                            currentUrl = newUrl
                            continue
                        }
                    }
                }

                val scriptTags = doc.select("script")
                for (script in scriptTags) {
                    val content = script.html()

                    val iframeMatch = Regex("thrfive\\.io/embed/([a-zA-Z0-9]+)").find(content)
                    if (iframeMatch != null) {
                        return "https://thrfive.io/embed/${iframeMatch.groups[1]?.value}?autoplay=false"
                    }

                    val locationMatch = Regex("window\\.location\\s*=\\s*['\"]([^'\"]+)['\"]").find(content)
                    if (locationMatch != null) {
                        currentReferer = currentUrl
                        val newUrl = locationMatch.groups[1]?.value
                        if (newUrl != null) {
                            currentUrl = newUrl
                            continue
                        }
                    }
                }

                val iframe = doc.selectFirst("iframe[src*='thrfive.io']")
                if (iframe != null) {
                    return iframe.attr("src")
                }

                break

            } catch (e: Exception) {
                return null
            }
        }

        return null
    }

    private suspend fun extractFromThrfive(embedUrl: String): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()

        try {
            val doc = app.get(
                embedUrl,
                headers = mapOf(
                    "Referer" to embedUrl.replace("/embed/", "/"),
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
                )
            ).document

            val scripts = doc.select("script")

            for (script in scripts) {
                val content = script.html()

                val setupRegex = Regex("jwplayer\\([^)]*\\)\\.setup\\(\\{([^}]+)\\}\\)")
                val setupMatch = setupRegex.find(content)
                if (setupMatch != null) {
                    val config = setupMatch.groups[1]?.value ?: continue
                    val sourceRegex = Regex("['\"]file['\"]\\s*:\\s*['\"]([^'\"]+)['\"]")
                    val sourceMatches = sourceRegex.findAll(config)

                    sourceMatches.forEach { match ->
                        val videoUrl = match.groups[1]?.value
                        if (videoUrl != null && (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4"))) {
                            links.add(
                                newExtractorLink(
                                    "ThiraiOne",
                                    "ThiraiOne - ${getQualityFromUrl(videoUrl)}p",
                                    videoUrl,
                                    embedUrl,
                                    getQualityFromUrl(videoUrl),
                                    videoUrl.contains(".m3u8")
                                )
                            )
                        }
                    }
                }

                val sourcesRegex = Regex("['\"]sources['\"]\\s*:\\s*\\[([^\\]]+)\\]")
                val sourcesMatch = sourcesRegex.find(content)
                if (sourcesMatch != null) {
                    val sourcesArray = sourcesMatch.groups[1]?.value ?: continue
                    val fileRegex = Regex("['\"]file['\"]\\s*:\\s*['\"]([^'\"]+)['\"]")
                    val fileMatches = fileRegex.findAll(sourcesArray)

                    fileMatches.forEach { match ->
                        val videoUrl = match.groups[1]?.value
                        if (videoUrl != null && (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4"))) {
                            links.add(
                                newExtractorLink(
                                    "ThiraiOne",
                                    "ThiraiOne - ${getQualityFromUrl(videoUrl)}p",
                                    videoUrl,
                                    embedUrl,
                                    getQualityFromUrl(videoUrl),
                                    videoUrl.contains(".m3u8")
                                )
                            )
                        }
                    }
                }

                val urlRegex = Regex("https://[^'\\"\\s]+\\.(?:m3u8|mp4)[^'\\"\\s]*")
                val urlMatches = urlRegex.findAll(content)

                urlMatches.forEach { match ->
                    val videoUrl = match.value
                    if (!links.any { it.url == videoUrl }) {
                        links.add(
                            newExtractorLink(
                                "ThiraiOne",
                                "ThiraiOne - ${getQualityFromUrl(videoUrl)}p",
                                videoUrl,
                                embedUrl,
                                getQualityFromUrl(videoUrl),
                                videoUrl.contains(".m3u8")
                            )
                        )
                    }
                }
            }

            if (links.isEmpty()) {
                val videoId = Regex("embed/([a-zA-Z0-9]+)").find(embedUrl)?.groups?.get(1)?.value
                if (videoId != null) {
                    val apiUrls = listOf(
                        "https://thrfive.io/api/source/$videoId",
                        "https://thrfive.io/player/index.php?data=$videoId",
                        "https://thrfive.io/api/video/$videoId"
                    )

                    for (apiUrl in apiUrls) {
                        try {
                            val apiResponse = app.get(
                                apiUrl,
                                headers = mapOf(
                                    "Referer" to embedUrl,
                                    "X-Requested-With" to "XMLHttpRequest"
                                )
                            ).text

                            val sourceRegex = Regex("\"(https://[^\"]+\\.(?:m3u8|mp4)[^\"]*)")
                            val matches = sourceRegex.findAll(apiResponse)

                            matches.forEach { match ->
                                val videoUrl = match.groups[1]?.value
                                if (videoUrl != null) {
                                    links.add(
                                        newExtractorLink(
                                            "ThiraiOne API",
                                            "ThiraiOne API - ${getQualityFromUrl(videoUrl)}p",
                                            videoUrl,
                                            embedUrl,
                                            getQualityFromUrl(videoUrl),
                                            videoUrl.contains(".m3u8")
                                        )
                                    )
                                }
                            }

                            if (links.isNotEmpty()) break
                        } catch (e: Exception) {
                        }
                    }
                }
            }

        } catch (e: Exception) {
        }

        return links
    }

    private fun getQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080") -> 1080
            url.contains("720") -> 720
            url.contains("480") -> 480
            url.contains("360") -> 360
            url.contains("240") -> 240
            else -> 0
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false

        try {
            val document = app.get(data).document

            val thiraiOneLinks = document.select("a[href*='tamilbliss.com']")

            for (link in thiraiOneLinks) {
                val href = link.attr("href")
                val videoIdMatch = Regex("video=([a-zA-Z0-9]+)").find(href)

                if (videoIdMatch != null) {
                    val videoId = videoIdMatch.groups[1]?.value ?: continue
                    val finalEmbedUrl = followRedirectChain(href, data)

                    if (finalEmbedUrl != null && finalEmbedUrl.contains("thrfive.io")) {
                        val extractedLinks = extractFromThrfive(finalEmbedUrl)

                        extractedLinks.forEach { extractorLink ->
                            callback.invoke(extractorLink)
                            foundLinks = true
                        }
                    }
                }
            }

            if (!foundLinks) {
                val allLinks = document.select("a[href*='dailymotion'], a[href*='youtube'], iframe[src*='player']")
                allLinks.forEach { element ->
                    val url = element.attr("href").ifEmpty { element.attr("src") }
                    if (url.isNotEmpty()) {
                        loadExtractor(url, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }

        } catch (e: Exception) {
            return false
        }

        return foundLinks
    }
}
