package recloudstream

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

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

    // Helper function to parse date for sorting
    private fun parseDate(dateString: String): Long {
        return try {
            val format = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // Helper function to extract date from episode title
    private fun extractDateFromTitle(title: String): String? {
        val datePattern = Regex("(\\d{1,2}-\\d{1,2}-\\d{4})")
        return datePattern.find(title)?.groups?.get(1)?.value
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val seriesList = mutableListOf<SearchResponse>()
        val processedUrls = mutableSetOf<String>()
        
        // Look for series main page links in the category pages
        val seriesLinks = document.select("a[href]").filter { element ->
            val href = element.attr("href")
            val text = element.text().trim()
            
            href.isNotEmpty() && 
            text.isNotEmpty() && 
            href.startsWith(mainUrl) &&
            // Series main pages don't have dates in URLs
            !href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) &&
            // Must be in the right category structure
            (href.contains("/vijay-tv-serial/") || 
             href.contains("/sun-tv-serial/") || 
             href.contains("/zee-tamil-serial/") ||
             href.contains("/colors-tamil-serial/") ||
             href.contains("/kalaignar-tv-serial/") ||
             href.contains("/vijay-tv-show/") ||
             href.contains("/sun-tv-show/")) &&
            href != request.data && // Not the category page itself
            text.length > 3 &&
            !processedUrls.contains(href)
        }
        
        seriesLinks.forEach { element ->
            val href = element.attr("href")
            val title = element.text().trim()
            val posterUrl = element.selectFirst("img")?.attr("src")
            
            if (!processedUrls.contains(href)) {
                processedUrls.add(href)
                seriesList.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                })
            }
        }
        
        // Fallback: Look in article structures
        if (seriesList.isEmpty()) {
            val articles = document.select("article.post, article.regular-post")
            
            articles.forEach { article ->
                val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
                val posterImg = article.selectFirst(".post-thumb img, img")
                
                if (titleLink != null) {
                    val href = titleLink.attr("href")
                    val title = titleLink.text().trim()
                    val posterUrl = posterImg?.attr("src")
                    
                    // Only get series main pages (no dates in URL)
                    if (href.isNotEmpty() && 
                        title.isNotEmpty() && 
                        href.startsWith(mainUrl) &&
                        !href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) &&
                        title.length > 3 &&
                        !processedUrls.contains(href)) {
                        
                        processedUrls.add(href)
                        seriesList.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
        }
        
        return newHomePageResponse(listOf(HomePageList(request.name, seriesList)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val processedUrls = mutableSetOf<String>()
        
        try {
            // Search through all main page categories to find matching series
            val mainPageUrls = listOf(
                "$mainUrl/vijay-tv/vijay-tv-serial/",
                "$mainUrl/sun-tv/sun-tv-serial/",
                "$mainUrl/zee-tamil/zee-tamil-serial/",
                "$mainUrl/colors-tamil/colors-tamil-serial/",
                "$mainUrl/kalaignar-tv/kalaignar-tv-serial/",
                "$mainUrl/vijay-tv/vijay-tv-show/",
                "$mainUrl/sun-tv/sun-tv-show/"
            )
            
            mainPageUrls.forEach { pageUrl ->
                try {
                    val document = app.get(pageUrl, timeout = 10).document
                    
                    // Look for series main page links
                    val seriesLinks = document.select("a[href]").filter { element ->
                        val href = element.attr("href")
                        val text = element.text().trim()
                        
                        text.contains(query, ignoreCase = true) &&
                        href.isNotEmpty() && 
                        href.startsWith(mainUrl) &&
                        !href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) && // No dates in URL
                        href != pageUrl &&
                        text.length > 3 &&
                        !processedUrls.contains(href)
                    }
                    
                    seriesLinks.forEach { element ->
                        val href = element.attr("href")
                        val title = element.text().trim()
                        val posterUrl = element.selectFirst("img")?.attr("src")
                        
                        if (!processedUrls.contains(href)) {
                            processedUrls.add(href)
                            searchResults.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                                this.posterUrl = posterUrl
                            })
                        }
                    }
                } catch (e: Exception) {
                    // Continue to next page if one fails
                }
            }
            
            // Also search using the site's search functionality
            val searchDocument = app.get("$mainUrl/?s=${query.replace(" ", "+")}", timeout = 10).document
            val articles = searchDocument.select("article.post, article.regular-post")
            
            articles.forEach { article ->
                val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
                val posterImg = article.selectFirst(".post-thumb img, img")
                
                if (titleLink != null) {
                    val href = titleLink.attr("href")
                    val title = titleLink.text().trim()
                    val posterUrl = posterImg?.attr("src")
                    
                    if (title.contains(query, ignoreCase = true) && 
                        href.isNotEmpty() && 
                        href.startsWith(mainUrl) &&
                        !href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) && // Series main page, not episode
                        title.length > 3 &&
                        !processedUrls.contains(href)) {
                        
                        processedUrls.add(href)
                        searchResults.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
        } catch (e: Exception) {
            // Return what we have if search fails
        }
        
        return searchResults.take(50)
    }

    override suspend fun load(url: String): LoadResponse? {
        // The URL is now the series main page (e.g., .../siragadikka-aasai/)
        val document = app.get(url).document
        val allEpisodes = mutableListOf<Episode>()
        
        // Extract series title
        val seriesTitle = document.selectFirst("title")?.text()?.let { titleText ->
            titleText.substringBefore(" - TamilDhool")
                .substringBefore(" - Tamil")
                .substringBefore(" Online")
                .trim()
        } ?: url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.titlecase() }
        
        // Extract poster
        val poster = document.selectFirst("img[src*='tamildhool'], meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img[src*='tamildhool']")?.attr("src")
            ?: document.selectFirst("img")?.attr("src")
        
        // Extract description
        val description = document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("p")?.text()
            ?: "Tamil serial episodes"
        
        // Find all episode links on this series page
        val episodeLinks = document.select("a[href]").filter { element ->
            val href = element.attr("href")
            val text = element.text().trim()
            
            href.isNotEmpty() && 
            text.isNotEmpty() && 
            href.startsWith(url) && // Episode URLs start with series URL
            href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) && // Contains date pattern
            text.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) && // Title also contains date
            text.length > 10
        }
        
        episodeLinks.forEachIndexed { index, element ->
            val href = element.attr("href")
            val title = element.text().trim()
            val posterUrl = element.selectFirst("img")?.attr("src") ?: poster
            
            // Extract date from title for sorting
            val dateString = extractDateFromTitle(title)
            
            allEpisodes.add(
                Episode(
                    data = href,
                    name = title,
                    episode = null, // Will be set after sorting
                    posterUrl = posterUrl,
                    description = if (dateString != null) "Episode aired on $dateString" else "Episode"
                )
            )
        }
        
        // Sort episodes by date (newest first)
        allEpisodes.sortByDescending { episode ->
            val dateString = extractDateFromTitle(episode.name ?: "")
            if (dateString != null) parseDate(dateString) else 0L
        }
        
        // Number episodes after sorting
        allEpisodes.forEachIndexed { index, episode ->
            episode.episode = index + 1
        }
        
        return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, allEpisodes) {
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
