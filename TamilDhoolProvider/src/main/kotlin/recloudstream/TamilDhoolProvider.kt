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
    override val supportedTypes = setOf(TvType.TvSeries) // Changed to TvSeries
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

    // Helper function to extract series name from episode title
    private fun extractSeriesName(episodeTitle: String): String {
        // Remove date patterns and common suffixes
        var seriesName = episodeTitle
            .replace(Regex("\\d{1,2}-\\d{1,2}-\\d{4}"), "") // Remove DD-MM-YYYY
            .replace(Regex("\\d{1,2}/\\d{1,2}/\\d{4}"), "") // Remove DD/MM/YYYY
            .replace(Regex("Vijay\\s*TV\\s*Serial", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Sun\\s*TV\\s*Serial", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Zee\\s*Tamil\\s*Serial", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Colors\\s*Tamil\\s*Serial", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Kalaignar\\s*TV\\s*Serial", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Serial\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Show\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Episode\\s*\\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Tamil\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        return seriesName.ifEmpty { "Unknown Series" }
    }

    // Helper function to extract date from title
    private fun extractDateFromTitle(title: String): String? {
        val datePattern = Regex("(\\d{1,2}-\\d{1,2}-\\d{4})")
        return datePattern.find(title)?.groups?.get(1)?.value
    }

    // Helper function to parse date for sorting
    private fun parseDate(dateString: String): Long {
        return try {
            val format = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val episodeMap = mutableMapOf<String, MutableList<Pair<String, String>>>() // seriesName -> List of (episodeTitle, episodeUrl)
        val seriesPosters = mutableMapOf<String, String>() // seriesName -> posterUrl
        val processedUrls = mutableSetOf<String>()
        
        // Method 1: Look for article posts with the specific structure
        val articles = document.select("article.post, article.regular-post")
        
        articles.forEach { article ->
            val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
            val posterImg = article.selectFirst(".post-thumb img, img")
            
            if (titleLink != null) {
                val href = titleLink.attr("href")
                val episodeTitle = titleLink.text().trim()
                val posterUrl = posterImg?.attr("src")
                
                if (href.isNotEmpty() && 
                    episodeTitle.isNotEmpty() && 
                    href.startsWith(mainUrl) &&
                    episodeTitle.length > 5 &&
                    !processedUrls.contains(href)) {
                    
                    processedUrls.add(href)
                    val seriesName = extractSeriesName(episodeTitle)
                    
                    // Add episode to series
                    if (!episodeMap.containsKey(seriesName)) {
                        episodeMap[seriesName] = mutableListOf()
                        if (posterUrl != null) {
                            seriesPosters[seriesName] = posterUrl
                        }
                    }
                    episodeMap[seriesName]?.add(Pair(episodeTitle, href))
                }
            }
        }
        
        // Method 2: Fallback - Look for episode links with date patterns
        if (episodeMap.isEmpty()) {
            val episodeLinks = document.select("a[href]").filter { element ->
                val href = element.attr("href")
                val text = element.text().trim()
                
                href.isNotEmpty() && 
                text.isNotEmpty() && 
                href.startsWith(mainUrl) &&
                href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) &&
                text.length > 5 &&
                !processedUrls.contains(href)
            }
            
            episodeLinks.forEach { element ->
                val href = element.attr("href")
                val episodeTitle = element.text().trim()
                val posterUrl = element.selectFirst("img")?.attr("src")
                
                if (!processedUrls.contains(href)) {
                    processedUrls.add(href)
                    val seriesName = extractSeriesName(episodeTitle)
                    
                    if (!episodeMap.containsKey(seriesName)) {
                        episodeMap[seriesName] = mutableListOf()
                        if (posterUrl != null) {
                            seriesPosters[seriesName] = posterUrl
                        }
                    }
                    episodeMap[seriesName]?.add(Pair(episodeTitle, href))
                }
            }
        }
        
        // Convert episodes map to SearchResponse list (one per series)
        val seriesList = episodeMap.map { (seriesName, episodes) ->
            newTvSeriesSearchResponse(seriesName, seriesName, TvType.TvSeries) {
                this.posterUrl = seriesPosters[seriesName]
            }
        }
        
        return newHomePageResponse(listOf(HomePageList(request.name, seriesList)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val episodeMap = mutableMapOf<String, MutableList<Pair<String, String>>>()
        val seriesPosters = mutableMapOf<String, String>()
        val processedUrls = mutableSetOf<String>()
        
        try {
            // Search using the site's search functionality
            val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}", timeout = 10).document
            
            // Look for article posts with the specific structure
            val articles = document.select("article.post, article.regular-post")
            
            articles.forEach { article ->
                val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
                val posterImg = article.selectFirst(".post-thumb img, img")
                
                if (titleLink != null) {
                    val href = titleLink.attr("href")
                    val episodeTitle = titleLink.text().trim()
                    val posterUrl = posterImg?.attr("src")
                    
                    val seriesName = extractSeriesName(episodeTitle)
                    if (seriesName.contains(query, ignoreCase = true) && 
                        href.isNotEmpty() && 
                        href.startsWith(mainUrl) &&
                        episodeTitle.length > 3 &&
                        !processedUrls.contains(href)) {
                        
                        processedUrls.add(href)
                        
                        if (!episodeMap.containsKey(seriesName)) {
                            episodeMap[seriesName] = mutableListOf()
                            if (posterUrl != null) {
                                seriesPosters[seriesName] = posterUrl
                            }
                        }
                        episodeMap[seriesName]?.add(Pair(episodeTitle, href))
                    }
                }
            }
            
            // Alternative search method - look through main categories
            if (episodeMap.isEmpty()) {
                val mainPageUrls = listOf(
                    "$mainUrl/vijay-tv/vijay-tv-serial/",
                    "$mainUrl/sun-tv/sun-tv-serial/",
                    "$mainUrl/zee-tamil/zee-tamil-serial/"
                )
                
                mainPageUrls.forEach { url ->
                    try {
                        val doc = app.get(url, timeout = 10).document
                        val articles = doc.select("article.post, article.regular-post")
                        
                        articles.forEach { article ->
                            val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
                            val posterImg = article.selectFirst(".post-thumb img, img")
                            
                            if (titleLink != null) {
                                val href = titleLink.attr("href")
                                val episodeTitle = titleLink.text().trim()
                                val posterUrl = posterImg?.attr("src")
                                
                                val seriesName = extractSeriesName(episodeTitle)
                                if (seriesName.contains(query, ignoreCase = true) && 
                                    href.isNotEmpty() && 
                                    href.startsWith(mainUrl) &&
                                    !processedUrls.contains(href)) {
                                    
                                    processedUrls.add(href)
                                    
                                    if (!episodeMap.containsKey(seriesName)) {
                                        episodeMap[seriesName] = mutableListOf()
                                        if (posterUrl != null) {
                                            seriesPosters[seriesName] = posterUrl
                                        }
                                    }
                                    episodeMap[seriesName]?.add(Pair(episodeTitle, href))
                                }
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
        
        // Convert to SearchResponse list
        val seriesList = episodeMap.map { (seriesName, episodes) ->
            newTvSeriesSearchResponse(seriesName, seriesName, TvType.TvSeries) {
                this.posterUrl = seriesPosters[seriesName]
            }
        }
        
        return seriesList.take(50)
    }

    override suspend fun load(url: String): LoadResponse? {
        // If url is a series name, we need to get all episodes for that series
        val seriesName = url
        val allEpisodes = mutableListOf<Episode>()
        
        // Search through all main page categories to find episodes for this series
        val mainPageUrls = listOf(
            "$mainUrl/vijay-tv/vijay-tv-serial/",
            "$mainUrl/sun-tv/sun-tv-serial/",
            "$mainUrl/zee-tamil/zee-tamil-serial/",
            "$mainUrl/colors-tamil/colors-tamil-serial/",
            "$mainUrl/kalaignar-tv/kalaignar-tv-serial/",
            "$mainUrl/vijay-tv/vijay-tv-show/",
            "$mainUrl/sun-tv/sun-tv-show/"
        )
        
        var seriesPoster: String? = null
        var seriesDescription = "Tamil serial episodes"
        
        mainPageUrls.forEach { pageUrl ->
            try {
                val document = app.get(pageUrl, timeout = 10).document
                val articles = document.select("article.post, article.regular-post")
                
                articles.forEach { article ->
                    val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
                    val posterImg = article.selectFirst(".post-thumb img, img")
                    
                    if (titleLink != null) {
                        val href = titleLink.attr("href")
                        val episodeTitle = titleLink.text().trim()
                        val posterUrl = posterImg?.attr("src")
                        
                        val extractedSeriesName = extractSeriesName(episodeTitle)
                        
                        if (extractedSeriesName.equals(seriesName, ignoreCase = true)) {
                            // Save series poster if we don't have one
                            if (seriesPoster == null && posterUrl != null) {
                                seriesPoster = posterUrl
                            }
                            
                            // Extract date and episode number
                            val dateString = extractDateFromTitle(episodeTitle)
                            val episodeNumber = if (dateString != null) {
                                parseDate(dateString).toInt()
                            } else {
                                allEpisodes.size + 1
                            }
                            
                            allEpisodes.add(
                                Episode(
                                    data = href,
                                    name = episodeTitle,
                                    episode = episodeNumber,
                                    posterUrl = posterUrl,
                                    description = "Episode aired on ${dateString ?: "Unknown date"}"
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue to next page if one fails
            }
        }
        
        // Sort episodes by date (newest first)
        allEpisodes.sortByDescending { episode ->
            val dateString = extractDateFromTitle(episode.name ?: "")
            if (dateString != null) parseDate(dateString) else 0L
        }
        
        // Re-number episodes after sorting
        allEpisodes.forEachIndexed { index, episode ->
            episode.episode = index + 1
        }
        
        return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, allEpisodes) {
            this.posterUrl = seriesPoster
            this.plot = seriesDescription
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
