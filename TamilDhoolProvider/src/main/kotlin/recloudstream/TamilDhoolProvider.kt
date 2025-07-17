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
import java.text.SimpleDateFormat
import java.util.*

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

    private fun extractSeriesName(title: String): String {
        // Remove common patterns to extract series name
        val cleanTitle = title
            .replace(Regex("\\d{2}-\\d{2}-\\d{4}"), "") // Remove date
            .replace(Regex("Episode \\d+", RegexOption.IGNORE_CASE), "") // Remove episode number
            .replace(Regex("\\d+/\\d+/\\d+"), "") // Remove date variants
            .replace(Regex("\\s+"), " ") // Normalize spaces
            .trim()
        
        // Common series name patterns
        val seriesPatterns = listOf(
            "Baakiyalakshmi", "Bharathi Kannamma", "Pandian Stores", "Roja", "Sembaruthi",
            "Yaaradi Nee Mohini", "Vanathai Pola", "Kanmani", "Nachiyarpuram", "Eeramana Rojave",
            "Kana Kaanum Kaalangal", "Saravanan Meenatchi", "Valli", "Thirumagal", "Poove Unakkaga"
        )
        
        // Find matching series name
        for (pattern in seriesPatterns) {
            if (cleanTitle.contains(pattern, ignoreCase = true)) {
                return pattern
            }
        }
        
        // Fallback: Extract first few words as series name
        val words = cleanTitle.split(" ")
        return if (words.size >= 2) {
            words.take(2).joinToString(" ")
        } else {
            cleanTitle
        }
    }

    private fun extractEpisodeInfo(title: String): Pair<String, Int?> {
        val seriesName = extractSeriesName(title)
        
        // Try to extract episode number
        val episodeMatch = Regex("Episode\\s+(\\d+)", RegexOption.IGNORE_CASE).find(title)
        val episodeNumber = episodeMatch?.groups?.get(1)?.value?.toIntOrNull()
        
        return Pair(seriesName, episodeNumber)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val seriesMap = mutableMapOf<String, MutableList<Episode>>()
        val processedUrls = mutableSetOf<String>()
        
        // Method 1: Look for article posts with the specific structure
        val articles = document.select("article.post, article.regular-post")
        
        articles.forEach { article ->
            val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
            val posterImg = article.selectFirst(".post-thumb img, img")
            
            if (titleLink != null) {
                val href = titleLink.attr("href")
                val title = titleLink.text().trim()
                val posterUrl = posterImg?.attr("src")
                
                if (href.isNotEmpty() && 
                    title.isNotEmpty() && 
                    href.startsWith(mainUrl) &&
                    title.length > 5 &&
                    !processedUrls.contains(href)) {
                    
                    processedUrls.add(href)
                    val (seriesName, episodeNumber) = extractEpisodeInfo(title)
                    
                    val episode = Episode(
                        data = href,
                        name = title,
                        episode = episodeNumber,
                        posterUrl = posterUrl
                    )
                    
                    seriesMap.getOrPut(seriesName) { mutableListOf() }.add(episode)
                }
            }
        }
        
        // Method 2: Fallback - Look for episode links with date patterns
        if (seriesMap.isEmpty()) {
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
                val title = element.text().trim()
                val posterUrl = element.selectFirst("img")?.attr("src")
                
                if (!processedUrls.contains(href)) {
                    processedUrls.add(href)
                    val (seriesName, episodeNumber) = extractEpisodeInfo(title)
                    
                    val episode = Episode(
                        data = href,
                        name = title,
                        episode = episodeNumber,
                        posterUrl = posterUrl
                    )
                    
                    seriesMap.getOrPut(seriesName) { mutableListOf() }.add(episode)
                }
            }
        }
        
        // Convert series map to search responses
        val seriesResults = seriesMap.map { (seriesName, episodes) ->
            // Sort episodes by episode number or by date in title
            val sortedEpisodes = episodes.sortedWith(compareBy(
                { it.episode ?: 0 },
                { it.name }
            ))
            
            // Use the first episode's poster as series poster
            val seriesPoster = sortedEpisodes.firstOrNull()?.posterUrl
            
            // Create a dummy URL for the series (we'll handle this in load())
            val seriesUrl = "$mainUrl/series/${seriesName.replace(" ", "-").lowercase()}"
            
            newTvSeriesSearchResponse(seriesName, seriesUrl, TvType.TvSeries) {
                this.posterUrl = seriesPoster
            }
        }
        
        return newHomePageResponse(listOf(HomePageList(request.name, seriesResults)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val seriesMap = mutableMapOf<String, MutableList<Episode>>()
        val processedUrls = mutableSetOf<String>()
        
        try {
            val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}", timeout = 10).document
            
            val articles = document.select("article.post, article.regular-post")
            
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
                        title.length > 3 &&
                        !processedUrls.contains(href)) {
                        
                        processedUrls.add(href)
                        val (seriesName, episodeNumber) = extractEpisodeInfo(title)
                        
                        val episode = Episode(
                            data = href,
                            name = title,
                            episode = episodeNumber,
                            posterUrl = posterUrl
                        )
                        
                        seriesMap.getOrPut(seriesName) { mutableListOf() }.add(episode)
                    }
                }
            }
            
            // Convert to search responses
            seriesMap.forEach { (seriesName, episodes) ->
                if (seriesName.contains(query, ignoreCase = true)) {
                    val seriesPoster = episodes.firstOrNull()?.posterUrl
                    val seriesUrl = "$mainUrl/series/${seriesName.replace(" ", "-").lowercase()}"
                    
                    searchResults.add(newTvSeriesSearchResponse(seriesName, seriesUrl, TvType.TvSeries) {
                        this.posterUrl = seriesPoster
                    })
                }
            }
            
        } catch (e: Exception) {
            // Return empty list if search fails
        }
        
        return searchResults.take(50)
    }

    override suspend fun load(url: String): LoadResponse? {
        // Check if this is a series URL (generated by us)
        if (url.contains("/series/")) {
            val seriesName = url.substringAfterLast("/").replace("-", " ")
                .replaceFirstChar { it.titlecase() }
            
            // We need to fetch episodes for this series
            val episodes = mutableListOf<Episode>()
            
            // Search through main pages to find episodes for this series
            val mainPageUrls = listOf(
                "$mainUrl/vijay-tv/vijay-tv-serial/",
                "$mainUrl/sun-tv/sun-tv-serial/",
                "$mainUrl/zee-tamil/zee-tamil-serial/",
                "$mainUrl/colors-tamil/colors-tamil-serial/",
                "$mainUrl/kalaignar-tv/kalaignar-tv-serial/"
            )
            
            mainPageUrls.forEach { pageUrl ->
                try {
                    val document = app.get(pageUrl).document
                    val articles = document.select("article.post, article.regular-post")
                    
                    articles.forEach { article ->
                        val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
                        val posterImg = article.selectFirst(".post-thumb img, img")
                        
                        if (titleLink != null) {
                            val href = titleLink.attr("href")
                            val title = titleLink.text().trim()
                            val posterUrl = posterImg?.attr("src")
                            
                            val (extractedSeriesName, episodeNumber) = extractEpisodeInfo(title)
                            
                            if (extractedSeriesName.equals(seriesName, ignoreCase = true)) {
                                episodes.add(Episode(
                                    data = href,
                                    name = title,
                                    episode = episodeNumber,
                                    posterUrl = posterUrl
                                ))
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Continue with other pages
                }
            }
            
            // Sort episodes
            val sortedEpisodes = episodes.sortedWith(compareBy(
                { it.episode ?: 0 },
                { it.name }
            ))
            
            return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = sortedEpisodes.firstOrNull()?.posterUrl
                this.plot = "Tamil TV Serial - $seriesName"
            }
        } else {
            // This is a direct episode URL, treat as single episode
            val document = app.get(url).document
            
            val title = document.selectFirst("title")?.text()?.let { titleText ->
                titleText.substringBefore(" - TamilDhool")
                    .substringBefore(" - Tamil")
                    .substringBefore(" Online")
                    .trim()
            } ?: document.selectFirst("h1, h2, h3")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.titlecase() }
            
            val poster = document.selectFirst("img[src*='tamildhool'], meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img[src*='tamildhool']")?.attr("src")
                ?: document.selectFirst("img")?.attr("src")
            
            val description = document.selectFirst("meta[name='description']")?.attr("content")
                ?: document.selectFirst("meta[property='og:description']")?.attr("content")
                ?: document.selectFirst("p")?.text()
                ?: "Tamil serial episode"
            
            val (seriesName, episodeNumber) = extractEpisodeInfo(title)
            
            return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, listOf(
                Episode(
                    data = url,
                    name = title,
                    episode = episodeNumber,
                    posterUrl = poster
                )
            )) {
                this.posterUrl = poster
                this.plot = description
            }
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
            
        } catch (e: Exception) {
            return false
        }
        
        return foundLinks
    }
}
