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
        
        // Extract all links from the page
        val links = document.select("a[href]")
        val processedUrls = mutableSetOf<String>()
        
        links.forEach { element ->
            val href = element.attr("href")
            val text = element.text().trim()
            
            // Check if this is a serial/show link and not an episode link
            if (href.isNotEmpty() && text.isNotEmpty() && 
                href.startsWith(mainUrl) && 
                !href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) && // Not an episode link
                href.contains(request.data.substringAfter(mainUrl)) && // Must be from current category
                href != request.data && // Not the category page itself
                !processedUrls.contains(href) && // Not already processed
                text.length > 3) { // Meaningful title
                
                processedUrls.add(href)
                
                // Try to get poster image
                val posterUrl = element.selectFirst("img")?.attr("src")
                
                shows.add(newTvSeriesSearchResponse(text, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                })
            }
        }
        
        // If no shows found with the above method, try to find them in article containers
        if (shows.isEmpty()) {
            val articles = document.select("article, div.post, div.content")
            
            articles.forEach { article ->
                val titleElement = article.selectFirst("h1, h2, h3, .title, .post-title")
                val linkElement = article.selectFirst("a[href]")
                val imageElement = article.selectFirst("img")
                
                if (titleElement != null && linkElement != null) {
                    val title = titleElement.text().trim()
                    val href = linkElement.attr("href")
                    val posterUrl = imageElement?.attr("src")
                    
                    if (title.isNotEmpty() && href.isNotEmpty() && 
                        href.startsWith(mainUrl) && 
                        !href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) &&
                        !processedUrls.contains(href)) {
                        
                        processedUrls.add(href)
                        shows.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
        }
        
        return newHomePageResponse(listOf(HomePageList(request.name, shows)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        val results = mutableListOf<SearchResponse>()
        val processedUrls = mutableSetOf<String>()
        
        // Look for search results
        val searchResults = document.select("a[href]")
        
        searchResults.forEach { element ->
            val text = element.text().trim()
            val href = element.attr("href")
            
            if (text.contains(query, ignoreCase = true) && 
                href.isNotEmpty() && 
                href.startsWith(mainUrl) &&
                (href.contains("/serial/") || href.contains("/show/")) &&
                !href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) &&
                !processedUrls.contains(href) &&
                text.length > 3) {
                
                processedUrls.add(href)
                val posterUrl = element.selectFirst("img")?.attr("src")
                
                results.add(newTvSeriesSearchResponse(text, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                })
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Extract title from page or URL
        val title = document.selectFirst("title")?.text()?.substringBefore(" - ")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.titlecase() }
        
        val poster = document.selectFirst("img")?.attr("src")
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("p")?.text()
        
        // Extract episodes - look for links that contain dates
        val episodes = mutableListOf<Episode>()
        val processedUrls = mutableSetOf<String>()
        
        // Get the base serial name from URL for matching episodes
        val serialName = url.substringAfterLast("/")
        
        // Look for episode links
        val episodeLinks = document.select("a[href]")
            .filter { element ->
                val href = element.attr("href")
                val text = element.text().trim()
                
                // Must contain date pattern and be related to this serial
                href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) && 
                href.contains(serialName) &&
                text.isNotEmpty() &&
                !processedUrls.contains(href)
            }
            .sortedByDescending { element ->
                // Sort by date - extract date from URL for proper sorting
                val dateMatch = Regex("(\\d{2}-\\d{2}-\\d{4})").find(element.attr("href"))
                dateMatch?.value ?: "00-00-0000"
            }
        
        episodeLinks.forEachIndexed { index, element ->
            val episodeTitle = element.text().trim()
            val episodeUrl = element.attr("href")
            
            if (!processedUrls.contains(episodeUrl)) {
                processedUrls.add(episodeUrl)
                
                // Extract episode number from date or use index
                val episodeNumber = episodes.size + 1
                
                episodes.add(Episode(
                    data = episodeUrl,
                    name = episodeTitle,
                    episode = episodeNumber
                ))
            }
        }
        
        // If no episodes found, try alternative method
        if (episodes.isEmpty()) {
            // Look for any links that might be episodes
            val allLinks = document.select("a[href*='$serialName']")
                .filter { element ->
                    val href = element.attr("href")
                    val text = element.text().trim()
                    
                    href != url && 
                    text.isNotEmpty() && 
                    href.startsWith(mainUrl) &&
                    !processedUrls.contains(href)
                }
                .take(100) // Limit to prevent too many episodes
            
            allLinks.forEachIndexed { index, element ->
                val episodeTitle = element.text().trim()
                val episodeUrl = element.attr("href")
                
                if (!processedUrls.contains(episodeUrl)) {
                    processedUrls.add(episodeUrl)
                    
                    episodes.add(Episode(
                        data = episodeUrl,
                        name = episodeTitle,
                        episode = index + 1
                    ))
                }
            }
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
        val document = app.get(data).document
        var foundLinks = false
        
        // Method 1: Look for TamilBliss links with video IDs
        val tamilBlissLinks = document.select("a[href*='tamilbliss.com']")
        tamilBlissLinks.forEach { link ->
            val href = link.attr("href")
            val videoIdMatch = Regex("video=([a-zA-Z0-9]+)").find(href)
            if (videoIdMatch != null) {
                val videoId = videoIdMatch.groups[1]?.value
                if (videoId != null) {
                    // Convert to Dailymotion embed URL
                    loadExtractor("https://www.dailymotion.com/embed/video/$videoId", subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }
        
        // Method 2: Look for Dailymotion thumbnail images to extract video IDs
        val dailymotionThumbnails = document.select("img[src*='dailymotion.com/thumbnail']")
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
        
        // Method 3: Look for prefetch links with Dailymotion IDs
        val prefetchLinks = document.select("link[href*='dai.ly']")
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
        
        // Method 4: Look for direct iframe embeds
        val iframes = document.select("iframe[src]")
        iframes.forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                when {
                    src.contains("dailymotion") -> {
                        val videoId = Regex("dailymotion.com/embed/video/([a-zA-Z0-9]+)").find(src)?.groups?.get(1)?.value
                        if (videoId != null) {
                            loadExtractor("https://www.dailymotion.com/embed/video/$videoId", subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                    src.contains("youtube") -> {
                        loadExtractor(src, subtitleCallback, callback)
                        foundLinks = true
                    }
                    src.contains("vimeo") -> {
                        loadExtractor(src, subtitleCallback, callback)
                        foundLinks = true
                    }
                    src.contains("player") || src.contains("embed") -> {
                        val fullUrl = if (src.startsWith("//")) "https:$src" else src
                        loadExtractor(fullUrl, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
        }
        
        // Method 5: Look in HTML content for video IDs
        val htmlContent = document.html()
        
        // Search for Dailymotion video IDs in various formats
        val videoIdPatterns = listOf(
            Regex("video=([a-zA-Z0-9]+)"),
            Regex("dai\\.ly/([a-zA-Z0-9]+)"),
            Regex("dailymotion\\.com/embed/video/([a-zA-Z0-9]+)"),
            Regex("dailymotion\\.com/video/([a-zA-Z0-9]+)"),
            Regex("thumbnail/\\d+x\\d+/video/([a-zA-Z0-9]+)")
        )
        
        videoIdPatterns.forEach { pattern ->
            val matches = pattern.findAll(htmlContent)
            matches.forEach { match ->
                val videoId = match.groups[1]?.value
                if (videoId != null && videoId.length > 5) {
                    loadExtractor("https://www.dailymotion.com/embed/video/$videoId", subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }
        
        // Method 6: Look for direct video links
        val videoElements = document.select("video source[src], a[href*='.mp4'], a[href*='.m3u8']")
        videoElements.forEach { element ->
            val src = element.attr("src").ifEmpty { element.attr("href") }
            if (src.isNotEmpty()) {
                loadExtractor(src, subtitleCallback, callback)
                foundLinks = true
            }
        }
        
        return foundLinks
    }
}
