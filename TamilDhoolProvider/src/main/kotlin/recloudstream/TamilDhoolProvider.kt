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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class TamilDhoolProvider : MainAPI() {
    override var mainUrl = "https://www.tamildhool.net"
    override var name = "TamilDhool"
    override val supportedTypes = setOf(TvType.Movie) // Changed to Movie
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
        val episodes = mutableListOf<SearchResponse>()
        val processedUrls = mutableSetOf<String>()
        
        // Method 1: Look for episode links with date patterns
        val episodeLinks = document.select("a[href]").filter { element ->
            val href = element.attr("href")
            val text = element.text().trim()
            
            href.isNotEmpty() && 
            text.isNotEmpty() && 
            href.startsWith(mainUrl) &&
            href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) && // Contains date pattern
            text.length > 5 &&
            !processedUrls.contains(href)
        }
        
        episodeLinks.forEach { element ->
            val href = element.attr("href")
            val title = element.text().trim()
            val posterUrl = element.selectFirst("img")?.attr("src")
            
            if (!processedUrls.contains(href)) {
                processedUrls.add(href)
                episodes.add(newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                })
            }
        }
        
        // Method 2: Look in article containers for episode links
        if (episodes.isEmpty()) {
            val articles = document.select("article, div.post, div.entry, div.content, li")
            
            articles.forEach { article ->
                val linkElement = article.selectFirst("a[href]")
                val titleElement = article.selectFirst("h1, h2, h3, h4, .title, .post-title, a")
                val imageElement = article.selectFirst("img")
                
                if (linkElement != null && titleElement != null) {
                    val href = linkElement.attr("href")
                    val title = titleElement.text().trim()
                    val posterUrl = imageElement?.attr("src")
                    
                    if (href.isNotEmpty() && 
                        title.isNotEmpty() && 
                        href.startsWith(mainUrl) &&
                        title.length > 5 &&
                        !processedUrls.contains(href) &&
                        (href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) || 
                         href.contains("/serial/") || 
                         href.contains("/show/"))) {
                        
                        processedUrls.add(href)
                        episodes.add(newMovieSearchResponse(title, href, TvType.Movie) {
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
        }
        
        // Method 3: Look for any recent episodes/shows
        if (episodes.isEmpty()) {
            val allLinks = document.select("a[href*='$mainUrl']").filter { element ->
                val href = element.attr("href")
                val text = element.text().trim()
                
                href != request.data && 
                text.isNotEmpty() && 
                text.length > 5 &&
                !processedUrls.contains(href) &&
                (text.contains("episode", ignoreCase = true) ||
                 text.contains("serial", ignoreCase = true) ||
                 text.contains("show", ignoreCase = true) ||
                 href.contains("/serial/") ||
                 href.contains("/show/"))
            }.take(20)
            
            allLinks.forEach { element ->
                val href = element.attr("href")
                val title = element.text().trim()
                val posterUrl = element.selectFirst("img")?.attr("src")
                
                if (!processedUrls.contains(href)) {
                    processedUrls.add(href)
                    episodes.add(newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                    })
                }
            }
        }
        
        return newHomePageResponse(listOf(HomePageList(request.name, episodes)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val processedUrls = mutableSetOf<String>()
        
        try {
            // Search using the site's search functionality
            val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}", timeout = 10).document
            
            // Look for search results
            val results = document.select("a[href]").filter { element ->
                val text = element.text().trim()
                val href = element.attr("href")
                
                text.contains(query, ignoreCase = true) && 
                href.isNotEmpty() && 
                href.startsWith(mainUrl) &&
                text.length > 3 &&
                !processedUrls.contains(href)
            }
            
            results.forEach { element ->
                val href = element.attr("href")
                val title = element.text().trim()
                val posterUrl = element.selectFirst("img")?.attr("src")
                
                if (!processedUrls.contains(href)) {
                    processedUrls.add(href)
                    searchResults.add(newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                    })
                }
            }
            
            // Alternative search method - look through main categories
            if (searchResults.isEmpty()) {
                val mainPageUrls = listOf(
                    "$mainUrl/vijay-tv/vijay-tv-serial/",
                    "$mainUrl/sun-tv/sun-tv-serial/",
                    "$mainUrl/zee-tamil/zee-tamil-serial/"
                )
                
                mainPageUrls.forEach { url ->
                    try {
                        val doc = app.get(url, timeout = 10).document
                        val links = doc.select("a[href]").filter { element ->
                            val text = element.text().trim()
                            val href = element.attr("href")
                            
                            text.contains(query, ignoreCase = true) && 
                            href.isNotEmpty() && 
                            href.startsWith(mainUrl) &&
                            !processedUrls.contains(href)
                        }
                        
                        links.forEach { element ->
                            val href = element.attr("href")
                            val title = element.text().trim()
                            val posterUrl = element.selectFirst("img")?.attr("src")
                            
                            if (!processedUrls.contains(href)) {
                                processedUrls.add(href)
                                searchResults.add(newMovieSearchResponse(title, href, TvType.Movie) {
                                    this.posterUrl = posterUrl
                                })
                            }
                        }
                    } catch (e: Exception) {
                        // Continue to next URL if one fails
                    }
                }
            }
        } catch (e: Exception) {
            // Return empty list if search fails
        }
        
        return searchResults.take(50) // Limit results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Extract title
        val title = document.selectFirst("title")?.text()?.let { titleText ->
            titleText.substringBefore(" - TamilDhool")
                .substringBefore(" - Tamil")
                .substringBefore(" Online")
                .trim()
        } ?: document.selectFirst("h1, h2, h3")?.text()?.trim()
        ?: url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.titlecase() }
        
        // Extract poster
        val poster = document.selectFirst("img[src*='tamildhool'], meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img[src*='tamildhool']")?.attr("src")
            ?: document.selectFirst("img")?.attr("src")
        
        // Extract description
        val description = document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("p")?.text()
            ?: "Tamil serial episode"
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
