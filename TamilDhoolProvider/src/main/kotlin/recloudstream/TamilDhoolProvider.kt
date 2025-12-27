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
            // METHOD 1: WebView with Play Button Click and Network Request Interception
            var capturedM3u8: String? = null
            
            val html = app.get(
                embedUrl,
                referer = refererUrl,
                interceptor = object : Interceptor {
                    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                        val request = chain.request()
                        val response = chain.proceed(request)
                        
                        // Intercept any request containing .m3u8
                        val url = request.url.toString()
                        if (url.contains(".m3u8") && 
                            (url.contains("coke.infamous.network") || url.contains("khufu.groovy.monster"))) {
                            capturedM3u8 = url
                        }
                        
                        // Also check response body for m3u8 URLs
                        if (response.body?.contentType()?.toString()?.contains("json") == true ||
                            response.body?.contentType()?.toString()?.contains("javascript") == true) {
                            val bodyString = response.body?.string() ?: ""
                            
                            val m3u8Match = Regex("""(https://(?:coke\.infamous\.network|khufu\.groovy\.monster)/stream/[^\s"']+\.m3u8)""")
                                .find(bodyString)
                            
                            if (m3u8Match != null) {
                                capturedM3u8 = m3u8Match.groupValues[1]
                            }
                            
                            // Recreate response with original body
                            return response.newBuilder()
                                .body(okhttp3.ResponseBody.create(response.body?.contentType(), bodyString))
                                .build()
                        }
                        
                        return response
                    }
                },
                webViewResolver = WebViewResolver(
                    // Wait for stream domain to appear
                    Regex("""(coke\.infamous\.network|khufu\.groovy\.monster|\.m3u8)"""),
                    // JavaScript to click play and wait for video to load
                    script = """
                        new Promise((resolve) => {
                            let resolved = false;
                            
                            // Function to try clicking play
                            function tryPlay() {
                                if (resolved) return;
                                
                                // Try multiple methods to click play
                                const playSelectors = [
                                    '.jw-display-icon-container',
                                    '.jw-icon-display',
                                    '.jw-display',
                                    'button[aria-label*="play"]',
                                    '.jw-controls-backdrop',
                                    '.jw-preview'
                                ];
                                
                                for (const selector of playSelectors) {
                                    const element = document.querySelector(selector);
                                    if (element) {
                                        element.click();
                                        break;
                                    }
                                }
                                
                                // Also try programmatic play if jwplayer is available
                                if (window.jwplayer) {
                                    try {
                                        jwplayer().play();
                                    } catch (e) {}
                                }
                            }
                            
                            // Wait for page to be ready
                            setTimeout(() => {
                                tryPlay();
                                
                                // Keep trying every second for 10 seconds
                                let attempts = 0;
                                const playInterval = setInterval(() => {
                                    attempts++;
                                    if (attempts >= 10 || resolved) {
                                        clearInterval(playInterval);
                                        if (!resolved) {
                                            resolved = true;
                                            resolve(document.documentElement.outerHTML);
                                        }
                                        return;
                                    }
                                    tryPlay();
                                }, 1000);
                                
                            }, 2000); // Wait 2 seconds for initial page load
                            
                            // Also set a maximum timeout
                            setTimeout(() => {
                                if (!resolved) {
                                    resolved = true;
                                    resolve(document.documentElement.outerHTML);
                                }
                            }, 15000); // 15 second max timeout
                        });
                    """.trimIndent()
                )
            ).text
            
            // Use captured m3u8 from network interception if available
            var m3u8Url = capturedM3u8
            
            // METHOD 2: Extract from HTML if network interception didn't work
            if (m3u8Url == null) {
                val m3u8Patterns = listOf(
                    Regex("""(https://coke\.infamous\.network/stream/[A-Za-z0-9+/=_-]+\.m3u8)"""),
                    Regex("""(https://khufu\.groovy\.monster/stream/[A-Za-z0-9+/=_-]+\.m3u8)"""),
                    Regex("""file["']?\s*:\s*["']([^"']*(?:coke\.infamous\.network|khufu\.groovy\.monster)[^"']*)["']"""),
                    Regex("""sources.*?["']([^"']*(?:coke\.infamous\.network|khufu\.groovy\.monster)[^"']*)["']"""),
                    Regex("""["'](https?://[^"']*(?:coke\.infamous\.network|khufu\.groovy\.monster)[^"']*\.m3u8[^"']*)["']""")
                )
                
                for (pattern in m3u8Patterns) {
                    val match = pattern.find(html)
                    if (match != null) {
                        var url = match.groupValues.lastOrNull { 
                            it.contains("network") || it.contains("monster") 
                        }
                        
                        if (!url.isNullOrEmpty()) {
                            url = url.trim().replace("\\", "")
                            if (url.startsWith("//")) url = "https:$url"
                            if (!url.endsWith(".m3u8") && url.contains("/stream/")) url = "$url.m3u8"
                            
                            m3u8Url = url
                            break
                        }
                    }
                }
            }
            
            // METHOD 3: Parse from script tags
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
                // Generate M3U8 links with proper referer and headers
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = refererUrl, // Use TamilDhool URL as referer
                    headers = mapOf(
                        "Origin" to mainUrl,
                        "Accept" to "*/*",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                ).forEach(callback)
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
