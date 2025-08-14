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
        val seriesMap = mutableMapOf<String, Pair<String, String?>>() // seriesUrl to (seriesName, posterUrl)
        
        // Scrape up to 3 pages of the category to discover more series
        for (i in 1..3) {
            val pageUrl = if (i == 1) request.data else "${request.data}page/$i/"
            val document = try { app.get(pageUrl, timeout = 30).document } catch (e: Exception) { continue }
            
            val articles = document.select("article.post, article.regular-post")
            
            articles.forEach { article ->
                val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
                val posterImg = article.selectFirst(".post-thumb img, img")
                
                if (titleLink != null) {
                    val epHref = titleLink.attr("href")
                    val epTitle = titleLink.text().trim()
                    val epPoster = posterImg?.attr("src")
                    
                    if (epHref.isNotEmpty() && epTitle.isNotEmpty() && epHref.startsWith(mainUrl)) {
                        val seriesUrl = epHref.substringBeforeLast("/") + "/"
                        val seriesName = epTitle.replace(Regex("\\s\\d{2}-\\d{2}-\\d{4}.*"), "").trim()
                        
                        if (seriesName.isNotEmpty() && !seriesMap.containsKey(seriesUrl)) {
                            seriesMap[seriesUrl] = seriesName to epPoster
                        }
                    }
                }
            }
        }
        
        val seriesList = seriesMap.map { (sUrl, data) ->
            val (name, poster) = data
            newTvSeriesSearchResponse(name, sUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        
        return newHomePageResponse(listOf(HomePageList(request.name, seriesList)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val seriesMap = mutableMapOf<String, Pair<String, String?>>() // seriesUrl to (seriesName, posterUrl)
        
        try {
            val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}", timeout = 30).document
            
            val articles = document.select("article.post, article.regular-post")
            
            articles.forEach { article ->
                val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
                val posterImg = article.selectFirst(".post-thumb img, img")
                
                if (titleLink != null) {
                    val epHref = titleLink.attr("href")
                    val epTitle = titleLink.text().trim()
                    val epPoster = posterImg?.attr("src")
                    
                    if (epTitle.contains(query, ignoreCase = true) && epHref.isNotEmpty() && epHref.startsWith(mainUrl)) {
                        val seriesUrl = epHref.substringBeforeLast("/") + "/"
                        val seriesName = epTitle.replace(Regex("\\s\\d{2}-\\d{2}-\\d{4}.*"), "").trim()
                        
                        if (seriesName.isNotEmpty() && !seriesMap.containsKey(seriesUrl)) {
                            seriesMap[seriesUrl] = seriesName to epPoster
                        }
                    }
                }
            }
            
            // Fallback if no results
            if (seriesMap.isEmpty()) {
                val results = document.select("a[href]").filter { element ->
                    val text = element.text().trim()
                    val href = element.attr("href")
                    text.contains(query, ignoreCase = true) && href.isNotEmpty() && href.startsWith(mainUrl)
                }
                
                results.forEach { element ->
                    val epHref = element.attr("href")
                    val epTitle = element.text().trim()
                    val epPoster = element.selectFirst("img")?.attr("src")
                    
                    val seriesUrl = epHref.substringBeforeLast("/") + "/"
                    val seriesName = epTitle.replace(Regex("\\s\\d{2}-\\d{2}-\\d{4}.*"), "").trim()
                    
                    if (seriesName.isNotEmpty() && !seriesMap.containsKey(seriesUrl)) {
                        seriesMap[seriesUrl] = seriesName to epPoster
                    }
                }
            }
            
            // Alternative: Search main categories if still empty
            if (seriesMap.isEmpty()) {
                val mainPageUrls = listOf(
                    "$mainUrl/vijay-tv/vijay-tv-serial/",
                    "$mainUrl/sun-tv/sun-tv-serial/",
                    "$mainUrl/zee-tamil/zee-tamil-serial/"
                )
                
                mainPageUrls.forEach { catUrl ->
                    try {
                        val doc = app.get(catUrl, timeout = 30).document
                        val articles = doc.select("article.post, article.regular-post")
                        
                        articles.forEach { article ->
                            val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
                            val posterImg = article.selectFirst(".post-thumb img, img")
                            
                            if (titleLink != null) {
                                val epHref = titleLink.attr("href")
                                val epTitle = titleLink.text().trim()
                                val epPoster = posterImg?.attr("src")
                                
                                if (epTitle.contains(query, ignoreCase = true) && epHref.isNotEmpty() && epHref.startsWith(mainUrl)) {
                                    val seriesUrl = epHref.substringBeforeLast("/") + "/"
                                    val seriesName = epTitle.replace(Regex("\\s\\d{2}-\\d{2}-\\d{4}.*"), "").trim()
                                    
                                    if (seriesName.isNotEmpty() && !seriesMap.containsKey(seriesUrl)) {
                                        seriesMap[seriesUrl] = seriesName to epPoster
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
        
        val seriesList = seriesMap.map { (sUrl, data) ->
            val (name, poster) = data
            newTvSeriesSearchResponse(name, sUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        
        return seriesList.take(50)
    }

    override suspend fun load(url: String): LoadResponse? {
        var currentUrl = url
        val episodes = mutableListOf<Episode>()
        val maxEpisodes = 200 // Limit to prevent timeouts on very long series
        
        try {
            val firstDoc = app.get(url, timeout = 30).document
            
            // Extract series title
            val title = firstDoc.selectFirst("title")?.text()?.let { titleText ->
                titleText.substringBefore(" - TamilDhool").trim()
            } ?: url.substringAfterLast("/").substringBeforeLast("/").replace("-", " ").replaceFirstChar { it.titlecase() }
            
            // Extract poster
            var poster = firstDoc.selectFirst("img[src*='tamildhool'], meta[property='og:image']")?.attr("content")
                ?: firstDoc.selectFirst("img[src*='tamildhool']")?.attr("src")
                ?: firstDoc.selectFirst("img")?.attr("src")
            
            // Extract description
            val description = firstDoc.selectFirst("meta[name='description']")?.attr("content")
                ?: firstDoc.selectFirst("meta[property='og:description']")?.attr("content")
                ?: firstDoc.selectFirst("p")?.text()
                ?: "Tamil serial"
            
            // Loop through all pagination pages
            while (episodes.size < maxEpisodes) {
                val document = app.get(currentUrl, timeout = 30).document
                
                val articles = document.select("article.post, article.regular-post")
                
                if (articles.isEmpty()) break
                
                articles.forEach { article ->
                    val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
                    val posterImg = article.selectFirst(".post-thumb img, img")
                    
                    if (titleLink != null) {
                        val epHref = titleLink.attr("href")
                        val epFullTitle = titleLink.text().trim()
                        val epPoster = posterImg?.attr("src")
                        
                        // Extract date as episode name
                        val dateMatch = Regex("\\d{2}-\\d{2}-\\d{4}").find(epFullTitle)
                        val epName = dateMatch?.value ?: epFullTitle.takeIf { it.isNotEmpty() } ?: "Episode"
                        
                        if (epHref.isNotEmpty() && epHref.startsWith(mainUrl)) {
                            episodes.add(Episode(epHref, name = epName, posterUrl = epPoster))
                        }
                    }
                }
                
                val nextUrl = document.selectFirst("a.next, .next")?.attr("href")
                if (nextUrl.isNullOrEmpty()) break
                currentUrl = nextUrl
            }
            
            // Sort episodes by date descending (convert dd-MM-yyyy to yyyy-MM-dd for comparison)
            val sortedEpisodes = episodes.sortedByDescending { ep ->
                Regex("\\d{2}-\\d{2}-\\d{4}").find(ep.name ?: "")?.value?.split("-")?.let { parts ->
                    "${parts[2]}-${parts[1]}-${parts[0]}"
                } ?: ""
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } catch (e: Exception) {
            return null
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
            val document = app.get(data, timeout = 30).document
            
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
            return false
        }
        
        return foundLinks
    }
}
