package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Base64
import okhttp3.Interceptor

class TamilDhoolProvider : MainAPI() {
    override var mainUrl = "https://www.tamildhool.tech"
    override var name = "TamilDhool"
    override val supportedTypes = setOf(TvType.Movie, TvType.Live)
    override var lang = "ta"
    override val hasMainPage = true

    private val SAI_BABA_LIVE = "SAI_BABA_LIVE_STREAM"
    private val SAI_BABA_PAGE = "https://sai.org.in/en/sai-video-popup"

    override val mainPage = mainPageOf(
        SAI_BABA_LIVE to "🔴 Live",
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
        if (request.data == SAI_BABA_LIVE) {
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = listOf(
                        newMovieSearchResponse(
                            name = "Sai Baba Temple Live",
                            url = SAI_BABA_LIVE,
                            type = TvType.Live
                        ) {
                            this.posterUrl = "https://via.placeholder.com/300x450/FF9933/FFFFFF?text=Sai+Baba+Live"
                        }
                    ),
                    isHorizontalImages = true
                ),
                hasNext = false
            )
        }

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
        if (url == SAI_BABA_LIVE) {
            return newMovieLoadResponse(
                name = "Sai Baba Temple Live",
                url = url,
                type = TvType.Live,
                dataUrl = url
            ) {
                this.posterUrl = "https://via.placeholder.com/300x450/FF9933/FFFFFF?text=Sai+Baba+Live"
                this.plot = "Live stream from Shirdi Sai Baba Temple"
            }
        }

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
        if (data == SAI_BABA_LIVE) {
            try {
                val doc = app.get(SAI_BABA_PAGE).document
                val videoSrc = doc.selectFirst("video#example-video_html5_api")?.attr("src")
                
                if (videoSrc != null) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "Sai Baba Live",
                            videoSrc,
                            SAI_BABA_PAGE,
                            Qualities.Unknown.value,
                            true
                        )
                    )
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false
        }

        var foundLinks = false
        
        try {
            val document = app.get(data).document

            // Method 1: TamilBliss links
            val tamilBlissLinks = document.select("a[href*='tamilbliss.com']")
            for (link in tamilBlissLinks) {
                val href = link.attr("href")
                val videoIdMatch = Regex("video=([^&]+)").find(href)
                
                if (videoIdMatch != null) {
                    val videoId = videoIdMatch.groups[1]?.value ?: continue
                    
                    if ((videoId.startsWith("k") || videoId.startsWith("x")) && videoId.length > 10) {
                        loadExtractor(
                            "https://www.dailymotion.com/video/$videoId",
                            subtitleCallback,
                            callback
                        )
                        foundLinks = true
                    } else {
                        val thrfiveUrl = "https://thrfive.io/embed/$videoId"
                        extractThrfive(thrfiveUrl, data, callback)
                        foundLinks = true
                    }
                }
            }
            
            // Method 2: Direct thrfive.io iframes
            val thrfiveIframes = document.select("iframe[src*='thrfive.io/embed/']")
            for (iframe in thrfiveIframes) {
                val embedUrl = iframe.attr("src")
                if (embedUrl.isNotEmpty()) {
                    extractThrfive(embedUrl, data, callback)
                    foundLinks = true
                }
            }
            
            // Method 3: Dailymotion embeds
            val dailymotionEmbeds = document.select("iframe[src*='dailymotion.com']")
            for (iframe in dailymotionEmbeds) {
                val src = iframe.attr("src")
                loadExtractor(src, subtitleCallback, callback)
                foundLinks = true
            }
            
            // Method 4: dai.ly short links
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
            val cookieJar = mutableMapOf<String, String>()
            
            val html = app.get(
                embedUrl,
                referer = refererUrl,
                interceptor = object : Interceptor {
                    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                        val request = chain.request()
                        val response = chain.proceed(request)
                        
                        response.headers("Set-Cookie").forEach { cookie ->
                            val parts = cookie.split(";")[0].split("=", limit = 2)
                            if (parts.size == 2) {
                                cookieJar[parts[0]] = parts[1]
                            }
                        }
                        
                        return response
                    }
                }
            ).text
            
            val m3u8Url = decodeJuicyCodes(html)
            
            if (m3u8Url != null) {
                val streamHeaders = mapOf(
                    "Origin" to "https://thrfive.io",
                    "Referer" to refererUrl,
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Cookie" to cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
                )
                
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = refererUrl,
                    headers = streamHeaders
                ).forEach(callback)
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun decodeJuicyCodes(html: String): String? {
        try {
            val juicyPattern = Regex("""_juicycodes\(((?:"[^"]*"\s*\+\s*)*"[^"]*")\)""")
            val match = juicyPattern.find(html) ?: return null
            
            val stringExpr = match.groupValues[1]
            val strings = Regex(""""([^"]*)"""").findAll(stringExpr).map { it.groupValues[1] }.toList()
            val encodedString = strings.joinToString("")
            
            val symbolMap = listOf("`", "%", "-", "+", "*", "$", "!", "_", "^", "=")
            
            val mainContent = encodedString.substring(0, encodedString.length - 3)
            val saltStr = encodedString.substring(encodedString.length - 3)
            
            val salt = saltStr.map { it.code - 100 }.joinToString("").toInt()
            
            val decodedB64 = try {
                val base64Str = mainContent.replace("_", "+").replace("-", "/")
                val padding = (4 - base64Str.length % 4) % 4
                val paddedStr = base64Str + "=".repeat(padding)
                String(Base64.getDecoder().decode(paddedStr))
            } catch (e: Exception) {
                return null
            }
            
            val rot13Decoded = decodedB64.map { char ->
                when {
                    char in 'a'..'z' -> ((char - 'a' + 13) % 26 + 'a'.code).toChar()
                    char in 'A'..'Z' -> ((char - 'A' + 13) % 26 + 'A'.code).toChar()
                    else -> char
                }
            }.joinToString("")
            
            val indices = rot13Decoded.mapNotNull { char ->
                symbolMap.indexOf(char.toString()).takeIf { it >= 0 }
            }.joinToString("")
            
            val groups = indices.chunked(4)
            val result = groups.mapNotNull { group ->
                if (group.length == 4) {
                    val num = group.toInt()
                    val decodedNum = (num % 1000) - salt
                    if (decodedNum in 0..1114111) {
                        decodedNum.toChar()
                    } else null
                } else null
            }.joinToString("")
            
            val m3u8Patterns = listOf(
                Regex("""(https://coke\.infamous\.network/stream/[A-Za-z0-9+/=_-]+\.m3u8)"""),
                Regex("""(https://khufu\.groovy\.monster/stream/[A-Za-z0-9+/=_-]+\.m3u8)"""),
                Regex("""file\s*:\s*["']([^"']*(?:coke\.infamous\.network|khufu\.groovy\.monster)[^"']*\.m3u8)["']"""),
                Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']*\.m3u8)["']""")
            )
            
            for (pattern in m3u8Patterns) {
                val urlMatch = pattern.find(result)
                if (urlMatch != null) {
                    var url = urlMatch.groupValues[1].replace("\\/", "/")
                    if (url.startsWith("//")) url = "https:$url"
                    if (!url.endsWith(".m3u8") && url.contains("/stream/")) url = "$url.m3u8"
                    return url
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return null
    }
}
