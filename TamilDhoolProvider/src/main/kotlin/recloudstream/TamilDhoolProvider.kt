package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TamilDhoolProvider : MainAPI() {
    override var mainUrl = "https://www.tamildhool.tech"
    override var name = "TamilDhool"
    override val supportedTypes = setOf(TvType.Movie)
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
        val doc = app.get(request.data + if (page > 1) "page/$page/" else "").document
        
        val episodes = doc.select("article.regular-post").mapNotNull { elem ->
            elem.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = episodes,
                isHorizontalImages = true
            ),
            hasNext = doc.selectFirst("a.next.page-numbers") != null
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawTitle = this.selectFirst("h3.entry-title a")?.text() ?: return null
        val title = cleanTitle(rawTitle)
        val href = fixUrlNull(this.selectFirst("h3.entry-title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("div.post-thumb img")?.attr("data-src-webp") 
                ?: this.selectFirst("div.post-thumb img")?.attr("src")
        )

        return newMovieSearchResponse(
            name = title,
            url = href,
            type = TvType.Movie
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        
        return doc.select("article.regular-post").mapNotNull { elem ->
            elem.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val rawTitle = doc.selectFirst("h1.entry-title")?.text() ?: "Unknown"
        val title = cleanTitle(rawTitle)
        
        val poster = doc.selectFirst("div.entry-cover")?.attr("style")?.let {
            Regex("url\\('([^']+)'\\)").find(it)?.groupValues?.get(1)
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        
        val description = doc.selectFirst("div.entry-content p")?.text()
        
        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            this.posterUrl = poster
            this.plot = description
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

            // Method 1: TamilBliss links - check for video parameter
            val tamilBlissLinks = document.select("a[href*='tamilbliss.com']")
            for (link in tamilBlissLinks) {
                val href = link.attr("href")
                val videoIdMatch = Regex("video=([^&]+)").find(href)
                
                if (videoIdMatch != null) {
                    val videoId = videoIdMatch.groups[1]?.value ?: continue
                    
                    // Check if it's Dailymotion (starts with 'k' or 'x')
                    if ((videoId.startsWith("k") || videoId.startsWith("x")) && videoId.length > 10) {
                        loadExtractor(
                            "https://www.dailymotion.com/video/$videoId",
                            subtitleCallback,
                            callback
                        )
                        foundLinks = true
                    } else {
                        // JW Player / Thrfive
                        val thrfiveUrl = "https://thrfive.io/embed/$videoId"
                        extractThrfive(thrfiveUrl, data, callback)
                        foundLinks = true
                    }
                }
            }
            
            // Method 2: Direct thrfive.io iframe detection
            val thrfiveIframes = document.select("iframe[src*='thrfive.io/embed/']")
            for (iframe in thrfiveIframes) {
                val embedUrl = iframe.attr("src")
                if (embedUrl.isNotEmpty()) {
                    extractThrfive(embedUrl, data, callback)
                    foundLinks = true
                }
            }
            
            // Method 3: Look for Dailymotion embeds
            val dailymotionEmbeds = document.select("iframe[src*='dailymotion.com']")
            for (iframe in dailymotionEmbeds) {
                val src = iframe.attr("src")
                loadExtractor(src, subtitleCallback, callback)
                foundLinks = true
            }
            
            // Method 4: Look for dai.ly short links
            val shortLinks = document.select("a[href*='dai.ly']")
            for (link in shortLinks) {
                val href = link.attr("href")
                val videoIdMatch = Regex("dai\\.ly/([a-zA-Z0-9]+)").find(href)
                if (videoIdMatch != null) {
                    val videoId = videoIdMatch.groups[1]?.value
                    if (videoId != null) {
                        loadExtractor(
                            "https://www.dailymotion.com/video/$videoId",
                            subtitleCallback,
                            callback
                        )
                        foundLinks = true
                    }
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return foundLinks
    }
    
    private suspend fun extractThrfive(embedUrl: String, refererUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Use WebView to bypass Cloudflare and wait for JW Player to load the M3U8
            val html = app.get(
                embedUrl,
                referer = refererUrl,
                interceptor = WebViewResolver(
                    // Wait for the stream domain to appear in the page
                    Regex("""(coke\.infamous\.network|khufu\.groovy\.monster)""")
                )
            ).text
            
            // Extract M3U8 URLs using multiple patterns
            val m3u8Patterns = listOf(
                // Direct M3U8 URLs in the page
                Regex("""(https://coke\.infamous\.network/stream/[A-Za-z0-9+/=_-]+\.m3u8)"""),
                Regex("""(https://khufu\.groovy\.monster/stream/[A-Za-z0-9+/=_-]+\.m3u8)"""),
                // URL-encoded in JW Player analytics
                Regex("""mu=([^&]+\.m3u8[^&]*)"""),
                // In jwplayer setup/config
                Regex("""file["']?\s*:\s*["']([^"']*(?:coke\.infamous\.network|khufu\.groovy\.monster)[^"']*)["']"""),
                // In sources array
                Regex("""sources.*?["']([^"']*(?:coke\.infamous\.network|khufu\.groovy\.monster)[^"']*)["']""")
            )
            
            var m3u8Url: String? = null
            
            for (pattern in m3u8Patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    var url = match.groupValues.lastOrNull { 
                        it.contains("network") || it.contains("monster") 
                    }
                    
                    if (!url.isNullOrEmpty()) {
                        // Decode if URL-encoded
                        if (url.contains("%2F") || url.contains("%3A")) {
                            try {
                                url = java.net.URLDecoder.decode(url, "UTF-8")
                            } catch (e: Exception) {
                                // Continue with non-decoded URL
                            }
                        }
                        
                        // Clean up
                        url = url.trim().replace("\\", "")
                        
                        if (url.startsWith("//")) {
                            url = "https:$url"
                        }
                        
                        // Ensure it ends with .m3u8
                        if (!url.endsWith(".m3u8") && url.contains("/stream/")) {
                            url = "$url.m3u8"
                        }
                        
                        m3u8Url = url
                        break
                    }
                }
            }
            
            // If still not found, look in script tags
            if (m3u8Url == null) {
                val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                val scripts = scriptPattern.findAll(html)
                
                for (script in scripts) {
                    val scriptContent = script.groupValues[1]
                    
                    val urlPattern = Regex("""(https://(?:coke\.infamous\.network|khufu\.groovy\.monster)/stream/[^\s"'<>]+)""")
                    val urlMatch = urlPattern.find(scriptContent)
                    
                    if (urlMatch != null) {
                        m3u8Url = urlMatch.groupValues[1]
                        if (!m3u8Url!!.endsWith(".m3u8")) {
                            m3u8Url = "$m3u8Url.m3u8"
                        }
                        break
                    }
                }
            }
            
            if (m3u8Url != null) {
                // Generate M3U8 links with TamilDhool as referer (CRITICAL!)
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = refererUrl, // Use TamilDhool URL, not thrfive!
                    headers = mapOf(
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                ).forEach(callback)
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
