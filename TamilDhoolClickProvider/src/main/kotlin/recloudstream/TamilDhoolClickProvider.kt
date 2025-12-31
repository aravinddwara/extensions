package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TamilDhoolClickProvider : MainAPI() {
    override var mainUrl = "https://tamildhool.click"
    override var name = "TamilDhool Click"
    override val supportedTypes = setOf(TvType.Movie, TvType.Live)
    override var lang = "ta"
    override val hasMainPage = true

    private val SAI_BABA_LIVE = "SAI_BABA_LIVE_STREAM"
    private val SAI_BABA_PAGE = "https://sai.org.in/en/sai-video-popup"

    override val mainPage = mainPageOf(
        SAI_BABA_LIVE to "🔴 Live",
        "$mainUrl/author/vijay-tv/" to "Vijay TV",
        "$mainUrl/author/zee-tamil/" to "Zee Tamil",
        "$mainUrl/author/sun-tv/" to "Sun TV"
    )

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("(?i)\\s*(Sun\\s*Tv|Vijay\\s*Tv|Zee\\s*Tamil)\\s*Free\\s*Serial\\s*$"), "")
            .replace(Regex("(?i)\\s*-\\s*(Sun\\s*Tv|Vijay\\s*Tv|Zee\\s*Tamil)\\s*$"), "")
            .trim()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == SAI_BABA_LIVE) {
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = listOf(
                        newMovieSearchResponse(
                            name = "Sai Baba Live",
                            url = SAI_BABA_LIVE,
                            type = TvType.Live
                        ) {
                            this.posterUrl = "https://i.ibb.co/zVQDvnnv/LIVE.jpg"
                        }
                    ),
                    isHorizontalImages = true
                ),
                hasNext = false
            )
        }

        val pageUrl = if (page > 1) "${request.data}page/$page/" else request.data
        val doc = app.get(pageUrl).document
        
        val episodes = doc.select("article.item-list.tie_video").mapNotNull { elem ->
            elem.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = episodes,
                isHorizontalImages = true
            ),
            hasNext = doc.selectFirst("a.next") != null
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawTitle = this.selectFirst("h2.post-box-title a")?.text() ?: return null
        val title = cleanTitle(rawTitle)
        val href = fixUrlNull(this.selectFirst("h2.post-box-title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.post-thumbnail img")?.attr("src"))

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
        
        return doc.select("article.item-list.tie_video").mapNotNull { elem ->
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
                this.posterUrl = "https://i.ibb.co/zVQDvnnv/LIVE.jpg"
                this.plot = "Shri Sai Baba of Shirdi, also known as Shirdi Sai Baba, Shree Sainath was an Indian spiritual guru considered to be a saint, and revered by both Hindu and Muslim devotees during and after his lifetime. Sai Baba preached the importance of realisation of the self and criticised love towards perishable things"
            }
        }

        val doc = app.get(url).document
        
        val rawTitle = doc.selectFirst("h1.post-title")?.text() ?: "Unknown"
        val title = cleanTitle(rawTitle)
        
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val description = doc.selectFirst("div.entry p")?.text()
        
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
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = videoSrc,
                        referer = SAI_BABA_PAGE
                    ).forEach(callback)
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

            // Find iframe with tamildhool.embed.lat
            val embedIframe = document.selectFirst("iframe[src*='tamildhool.embed.lat/player.php']")
            if (embedIframe != null) {
                val embedSrc = embedIframe.attr("src")
                val videoIdMatch = Regex("vid=([a-zA-Z0-9]+)").find(embedSrc)
                
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

            // Fallback: Direct Dailymotion iframes
            if (!foundLinks) {
                val dailymotionEmbeds = document.select("iframe[src*='dailymotion.com']")
                for (iframe in dailymotionEmbeds) {
                    val src = iframe.attr("src")
                    loadExtractor(src, subtitleCallback, callback)
                    foundLinks = true
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return foundLinks
    }
}
