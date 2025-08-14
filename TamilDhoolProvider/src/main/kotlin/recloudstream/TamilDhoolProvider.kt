package recloudstream

import com.lagradost.cloudstream3.Episode
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
        "$mainUrl/vijay-tv/vijay-tv-serial/" to "Vijay TV Serials",
        "$mainUrl/sun-tv/sun-tv-serial/" to "Sun TV Serials",
        "$mainUrl/zee-tamil/zee-tamil-serial/" to "Zee Tamil Serials",
        "$mainUrl/colors-tamil/colors-tamil-serial/" to "Colors Tamil Serials",
        "$mainUrl/kalaignar-tv/kalaignar-tv-serial/" to "Kalaignar TV Serials",
        "$mainUrl/vijay-tv/vijay-tv-show/" to "Vijay TV Shows",
        "$mainUrl/sun-tv/sun-tv-show/" to "Sun TV Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val shows = mutableListOf<SearchResponse>()
        val processedUrls = mutableSetOf<String>()

        // Extract serial/show items from the grid
        val serialItems = document.select("div.td_block_inner .td-module-thumb, div.td-category-grid .td-module-thumb")

        serialItems.forEach { item ->
            val linkElement = item.selectFirst("a")
            val href = linkElement?.attr("href") ?: return@forEach
            val title = linkElement.attr("title").trim().ifEmpty { item.selectFirst("img")?.attr("alt")?.trim() } ?: return@forEach
            val posterUrl = item.selectFirst("img")?.attr("src")

            if (href.isNotEmpty() &&
                title.isNotEmpty() &&
                href.startsWith(mainUrl) &&
                !href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) && // Exclude episode links with dates
                title.length > 5 &&
                !processedUrls.contains(href)) {

                processedUrls.add(href)
                shows.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                })
            }
        }

        // Fallback: Look for links without date patterns
        if (shows.isEmpty()) {
            val serialLinks = document.select("a[href]").filter { element ->
                val href = element.attr("href")
                val text = element.attr("title").trim().ifEmpty { element.text().trim() }

                href.startsWith("$mainUrl/") &&
                (href.contains("/serial/") || href.contains("/show/")) &&
                !href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) &&
                text.length > 5 &&
                !processedUrls.contains(href)
            }

            serialLinks.forEach { element ->
                val href = element.attr("href")
                val title = element.attr("title").trim().ifEmpty { element.text().trim() }
                val posterUrl = element.selectFirst("img")?.attr("src") ?: element.parent()?.selectFirst("img")?.attr("src")

                if (title.isNotEmpty() && !processedUrls.contains(href)) {
                    processedUrls.add(href)
                    shows.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    })
                }
            }
        }

        return newHomePageResponse(listOf(HomePageList(request.name, shows)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val processedUrls = mutableSetOf<String>()

        // Loop through main categories to find matching shows
        mainPage.forEach { pageEntry ->
            try {
                val url = pageEntry.first
                val document = app.get(url, timeout = 10).document

                val serialItems = document.select("div.td_block_inner .td-module-thumb, div.td-category-grid .td-module-thumb")

                serialItems.forEach { item ->
                    val linkElement = item.selectFirst("a")
                    val href = linkElement?.attr("href") ?: return@forEach
                    val title = linkElement.attr("title").trim().ifEmpty { item.selectFirst("img")?.attr("alt")?.trim() } ?: return@forEach
                    val posterUrl = item.selectFirst("img")?.attr("src")

                    if (title.contains(query, ignoreCase = true) &&
                        href.isNotEmpty() &&
                        href.startsWith(mainUrl) &&
                        !href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) &&
                        !processedUrls.contains(href)) {

                        processedUrls.add(href)
                        searchResults.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        })
                    }
                }

                // Fallback within category
                if (serialItems.isEmpty()) {
                    val fallbackLinks = document.select("a[href]").filter { element ->
                        val href = element.attr("href")
                        val title = element.attr("title").trim().ifEmpty { element.text().trim() }

                        title.contains(query, ignoreCase = true) &&
                        href.startsWith("$mainUrl/") &&
                        (href.contains("/serial/") || href.contains("/show/")) &&
                        !href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) &&
                        !processedUrls.contains(href)
                    }

                    fallbackLinks.forEach { element ->
                        val href = element.attr("href")
                        val title = element.attr("title").trim().ifEmpty { element.text().trim() }
                        val posterUrl = element.selectFirst("img")?.attr("src") ?: element.parent()?.selectFirst("img")?.attr("src")

                        if (title.isNotEmpty() && !processedUrls.contains(href)) {
                            processedUrls.add(href)
                            searchResults.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                                this.posterUrl = posterUrl
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip if category fails
            }
        }

        return searchResults.take(50)
    }

    override suspend fun load(url: String): LoadResponse? {
        var currentPage = 1
        val episodes = mutableListOf<Episode>()
        val processedUrls = mutableSetOf<String>()

        // Extract show details from first page
        val firstDocument = app.get(url).document

        val title = firstDocument.selectFirst("title")?.text()?.let { titleText ->
            titleText.substringBefore(" - TamilDhool")
                .substringBefore(" - Tamil")
                .substringBefore(" Online")
                .trim()
        } ?: firstDocument.selectFirst("h1, h2, h3")?.text()?.trim()
        ?: url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.titlecase() }

        val poster = firstDocument.selectFirst("img[src*='tamildhool'], meta[property='og:image']")?.attr("content")
            ?: firstDocument.selectFirst("img[src*='tamildhool']")?.attr("src")
            ?: firstDocument.selectFirst("img")?.attr("src")

        val description = firstDocument.selectFirst("meta[name='description']")?.attr("content")
            ?: firstDocument.selectFirst("meta[property='og:description']")?.attr("content")
            ?: firstDocument.selectFirst("p")?.text()
            ?: "Tamil serial"

        // Loop through pagination to get all episodes
        while (true) {
            val document = if (currentPage == 1) firstDocument else app.get("$url/page/$currentPage/").document
            val articleElements = document.select("article.post, article.regular-post")

            if (articleElements.isEmpty()) break

            articleElements.forEach { article ->
                val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
                val episodeHref = titleLink?.attr("href") ?: return@forEach
                val episodeTitle = titleLink.text().trim()
                val episodePoster = article.selectFirst(".post-thumb img, img")?.attr("src")

                if (episodeHref.isNotEmpty() &&
                    episodeTitle.isNotEmpty() &&
                    episodeHref.startsWith(mainUrl) &&
                    !processedUrls.contains(episodeHref)) {

                    processedUrls.add(episodeHref)
                    episodes.add(Episode(episodeHref, episodeTitle, posterUrl = episodePoster))
                }
            }

            currentPage++
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
            
            // Method 1: Look for TamilBliss links with video IDs
            val tamilBlissLinks = document.select("a[href*='tamilbliss.com']")
            tamilBlissLinks.forEach { link ->
                val href = link.attr("href")
                val videoIdMatch = Regex("video=([a-zA-Z0-9]+)").find(href)
                if (videoIdMatch != null) {
                    val videoId = videoIdMatch.groups[1]?.value
                    if (videoId != null) {
                        loadExtractor("https://www.dailymotion.com/embed/video/$videoId", subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
            
            // Method 2: Look for Dailymotion thumbnail images
            val dailymotionThumbnails = document.select("img[src*='dailymotion.com']")
            dailymotionThumbnails.forEach { img ->
                val src = img.attr("src")
                val videoIdMatch = Regex("video/([a-zA-Z0-9]+)").find(src)
                if (videoIdMatch != null) {
                    val videoId = videoIdMatch.groups[1]?.value
                    if (videoId != null) {
                        loadExtractor("https://www.dailymotion.com/embed/video/$videoId", subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
            
            // Method 3: Look for iframe embeds
            val iframes = document.select("iframe[src]")
            iframes.forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    val fullUrl = if (src.startsWith("//")) "https:$src" else src
                    if (fullUrl.contains("dailymotion") || fullUrl.contains("youtube") || 
                        fullUrl.contains("vimeo") || fullUrl.contains("player")) {
                        loadExtractor(fullUrl, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
            
            // Method 4: Search HTML content for video IDs
            val htmlContent = document.html()
            val videoIdPatterns = listOf(
                Regex("video=([a-zA-Z0-9]+)"),
                Regex("dai\\.ly/([a-zA-Z0-9]+)"),
                Regex("dailymotion\\.com/embed/video/([a-zA-Z0-9]+)"),
                Regex("dailymotion\\.com/video/([a-zA-Z0-9]+)"),
                Regex("thumbnail/video/([a-zA-Z0-9]+)")
            )
            
            videoIdPatterns.forEach { pattern ->
                val matches = pattern.findAll(htmlContent)
                matches.forEach { match ->
                    val videoId = match.groups[1]?.value
                    if (videoId != null && videoId.length >= 6) {
                        loadExtractor("https://www.dailymotion.com/embed/video/$videoId", subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
            
            // Method 5: Look for direct video links
            val videoElements = document.select("video source[src], a[href*='.mp4'], a[href*='.m3u8']")
            videoElements.forEach { element ->
                val src = element.attr("src").ifEmpty { element.attr("href") }
                if (src.isNotEmpty()) {
                    loadExtractor(src, subtitleCallback, callback)
                    foundLinks = true
                }
            }
            
            // Method 6: Look for prefetch or preload links
            val prefetchLinks = document.select("link[href*='dai.ly'], link[href*='dailymotion']")
            prefetchLinks.forEach { link ->
                val href = link.attr("href")
                val videoIdMatch = Regex("dai\\.ly/([a-zA-Z0-9]+)").find(href)
                if (videoIdMatch != null) {
                    val videoId = videoIdMatch.groups[1]?.value
                    if (videoId != null) {
                        loadExtractor("https://www.dailymotion.com/embed/video/$videoId", subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
            
        } catch (e: Exception) {
            // Log error but don't crash
            return false
        }
        
        return foundLinks
    }
}
