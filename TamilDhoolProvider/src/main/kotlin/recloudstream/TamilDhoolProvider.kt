package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TamilDhoolProvider : MainAPI() {
    override var mainUrl = "https://tamildhool.tech"
    override var name = "TamilDhool"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "ta"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/sun-tv/" to "Sun TV",
        "$mainUrl/vijay-tv/" to "Vijay TV", 
        "$mainUrl/zee-tamil/" to "Zee Tamil",
        "$mainUrl/kalaignar-tv/" to "Kalaignar TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + "page/$page/").document
        val episodes = doc.select("article.regular-post").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(
            list = listOf(HomePageList(request.name, episodes, true)),
            hasNext = doc.selectFirst(".navigation .next") != null
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.entry-title a")?.text() ?: return null
        val href = this.selectFirst("h3.entry-title a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst(".post-thumb img")?.attr("src")
        val category = this.selectFirst(".cat-links a")?.text()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            if (category != null) {
                this.tags = listOf(category)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(searchUrl).document
        return doc.select("article.regular-post").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1.entry-title")?.text() ?: return null
        val description = doc.select(".entry-content p").firstOrNull()?.text()
        val posterUrl = doc.selectFirst(".entry-cover")?.attr("style")?.let {
            Regex("background-image:url\\('(.*?)'\\)").find(it)?.groupValues?.get(1)
        } ?: doc.selectFirst("img")?.attr("src")
        
        val category = doc.selectFirst(".entry-category a")?.text()
        val tags = listOfNotNull(category)
        
        // Extract show name and episode info from title
        val showName = title.split(" \\d".toRegex()).firstOrNull() ?: title
        val episodes = listOf(
            Episode(
                data = url,
                name = title,
                episode = extractEpisodeNumber(title)
            )
        )

        return newTvSeriesLoadResponse(showName, url, TvType.TvSeries, episodes) {
            this.plot = description
            this.posterUrl = posterUrl
            this.tags = tags
        }
    }

    private fun extractEpisodeNumber(title: String): Int? {
        val regex = "\\d{2}-\\d{2}-\\d{4}".toRegex()
        val match = regex.find(title)
        return match?.let {
            // Convert date to episode number (simple approach)
            val dateParts = it.value.split("-")
            val day = dateParts[0].toIntOrNull() ?: 1
            val month = dateParts[1].toIntOrNull() ?: 1
            // Create a simple episode number from date
            (month * 100) + day
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        // Extract iframe sources
        val iframes = doc.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            when {
                src.contains("thrfive.io") -> {
                    loadExtractor(src, subtitleCallback, callback)
                }
                src.contains("dailymotion") || src.contains("dai.ly") -> {
                    loadExtractor(src, subtitleCallback, callback)
                }
            }
        }
        
        // Extract Dailymotion links from custom player links
        val dailymotionLinks = doc.select("a[href*='tamilbliss.com']")
        for (link in dailymotionLinks) {
            val href = link.attr("href")
            val videoId = Regex("video=([a-zA-Z0-9]+)").find(href)?.groupValues?.get(1)
            if (videoId != null) {
                loadExtractor("https://www.dailymotion.com/video/$videoId", subtitleCallback, callback)
            }
        }
        
        // Look for direct video sources in the page content
        val pageContent = doc.html()
        val dailymotionMatches = Regex("k[a-zA-Z0-9]+").findAll(pageContent)
        for (match in dailymotionMatches) {
            val videoId = match.value
            if (videoId.length > 10) { // Dailymotion IDs are typically longer
                loadExtractor("https://www.dailymotion.com/video/$videoId", subtitleCallback, callback)
            }
        }

        return true
    }
}
