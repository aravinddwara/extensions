package recloudstream

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class TamilDhoolProvider : MainAPI() {
    override var mainUrl = "https://www.tamildhool.net"
    override var name = "TamilDhool"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "ta"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/vijay-tv/" to "Vijay TV",
        "$mainUrl/sun-tv/" to "Sun TV",
        "$mainUrl/zee-tamil/" to "Zee Tamil",
        "$mainUrl/colors-tamil/" to "Colors Tamil",
        "$mainUrl/kalaignar-tv/" to "Kalaignar TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div.post-content article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.post-title a")?.text() ?: return null
        val href = this.selectFirst("h2.post-title a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.post-content article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.post-title")?.text() ?: return null
        val poster = document.selectFirst("img")?.attr("src")
        val description = document.selectFirst("div.post-content p")?.text()
        
        // Extract episodes from the show page
        val episodes = document.select("div.post-content a").mapNotNull { element ->
            val episodeTitle = element.text()
            val episodeUrl = element.attr("href")
            if (episodeUrl.isNotEmpty() && episodeTitle.isNotEmpty()) {
                Episode(episodeUrl, episodeTitle)
            } else null
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    data class Episode(
        val url: String,
        val name: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Look for Dailymotion embed iframes
        val iframes = document.select("iframe[src*=dailymotion]")
        
        iframes.forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                // Extract Dailymotion video ID from embed URL
                val videoId = Regex("dailymotion.com/embed/video/([a-zA-Z0-9]+)").find(src)?.groups?.get(1)?.value
                if (videoId != null) {
                    // Use the existing Dailymotion extractor
                    loadExtractor("https://www.dailymotion.com/embed/video/$videoId", subtitleCallback, callback)
                }
            }
        }
        
        // Also check for any other video embeds or direct links
        val videoLinks = document.select("iframe[src*=video], iframe[src*=embed]")
        videoLinks.forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && src.contains("dailymotion")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
