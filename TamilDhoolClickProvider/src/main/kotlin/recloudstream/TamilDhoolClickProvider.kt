package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TamilDhoolClickProvider : MainAPI() {
    override var mainUrl = "https://tamildhool.click"
    override var name = "TamilDhool Click"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "ta"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
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
        try {
            val document = app.get(data).document

            // Method 1: Find iframe in single-post-video div
            document.select("div.single-post-video iframe, iframe").forEach { iframe ->
                val src = iframe.attr("src")
                
                // Extract from tamildhool.embed.lat
                if (src.contains("tamildhool.embed.lat")) {
                    val videoIdMatch = Regex("vid=([^&\"'\\s]+)").find(src)
                    if (videoIdMatch != null) {
                        val videoId = videoIdMatch.groups[1]?.value
                        if (!videoId.isNullOrEmpty()) {
                            loadExtractor(
                                "https://www.dailymotion.com/embed/video/$videoId",
                                subtitleCallback,
                                callback
                            )
                            return true
                        }
                    }
                }
                
                // Direct Dailymotion embed
                if (src.contains("dailymotion.com")) {
                    loadExtractor(src, subtitleCallback, callback)
                    return true
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return false
    }
}
