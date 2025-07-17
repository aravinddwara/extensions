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

    // Enhanced series name extraction with better pattern matching
    private fun extractSeriesName(title: String): String {
        val cleanTitle = title
            .replace(Regex("\\d{2}-\\d{2}-\\d{4}"), "")
            .replace(Regex("\\d{2}/\\d{2}/\\d{4}"), "")
            .replace(Regex("Episode\\s+\\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Vijay\\s+Tv\\s+Serial", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Sun\\s+Tv\\s+Serial", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Zee\\s+Tamil\\s+Serial", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Colors\\s+Tamil\\s+Serial", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Known series patterns for better matching
        val seriesPatterns = listOf(
            "Baakiyalakshmi", "Bharathi Kannamma", "Pandian Stores", "Roja", "Sembaruthi",
            "Yaaradi Nee Mohini", "Vanathai Pola", "Kanmani", "Nachiyarpuram", "Eeramana Rojave",
            "Kana Kaanum Kaalangal", "Saravanan Meenatchi", "Valli", "Thirumagal", "Poove Unakkaga",
            "Mullum Malarum", "Azhagu", "Deivam Thandha Veedu", "Kalyana Parisu", "Magarasi",
            "Nenjam Marappathillai", "Chithi", "Sindhu Bhairavi", "Rasaathi", "Chinna Thambi"
        )

        // Find exact match first
        for (pattern in seriesPatterns) {
            if (cleanTitle.contains(pattern, ignoreCase = true)) {
                return pattern
            }
        }

        // Fallback: Use first 2-3 meaningful words
        val words = cleanTitle.split(" ").filter { it.length > 2 }
        return if (words.size >= 2) {
            words.take(2).joinToString(" ")
        } else {
            cleanTitle.take(50)
        }
    }

    // Enhanced episode info extraction with date parsing
    private fun extractEpisodeInfo(title: String): Triple<String, Int?, String?> {
        val seriesName = extractSeriesName(title)
        
        // Extract episode number
        val episodeMatch = Regex("Episode\\s+(\\d+)", RegexOption.IGNORE_CASE).find(title)
        val episodeNumber = episodeMatch?.groups?.get(1)?.value?.toIntOrNull()
        
        // Extract date from title
        val dateMatch = Regex("(\\d{2}-\\d{2}-\\d{4})").find(title)
        val episodeDate = dateMatch?.groups?.get(1)?.value
        
        return Triple(seriesName, episodeNumber, episodeDate)
    }

    // Generate landscape thumbnail URL (16:9 aspect ratio)
    private fun generateLandscapeThumbnail(originalUrl: String?): String? {
        return originalUrl?.let { url ->
            if (url.contains("tamildhool")) {
                // Try to modify URL for landscape thumbnail
                url.replace("_thumb", "_landscape")
                   .replace("150x150", "320x180")
                   .replace("200x200", "320x180")
            } else {
                // For external images, return as-is
                url
            }
        }
    }

    // Enhanced main page with better episode organization
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val seriesMap = mutableMapOf<String, MutableList<Episode>>()
        val processedUrls = mutableSetOf<String>()
        
        // Method 1: Enhanced article parsing with better selectors
        val articles = document.select("article.post, article.regular-post, .post-item, .entry")
        
        articles.forEach { article ->
            val titleLink = article.selectFirst("h3.entry-title a, .entry-title a, h2 a, h1 a")
                ?: article.selectFirst("a[href*='${request.data}']")
            
            val posterImg = article.selectFirst(".post-thumb img, .entry-image img, .featured-image img, img")
            
            if (titleLink != null) {
                val href = titleLink.attr("href")
                val title = titleLink.text().trim()
                val originalPosterUrl = posterImg?.attr("src") ?: posterImg?.attr("data-src")
                val posterUrl = generateLandscapeThumbnail(originalPosterUrl)
                
                if (href.isNotEmpty() && 
                    title.isNotEmpty() && 
                    href.startsWith(mainUrl) &&
                    title.length > 5 &&
                    !processedUrls.contains(href)) {
                    
                    processedUrls.add(href)
                    val (seriesName, episodeNumber, episodeDate) = extractEpisodeInfo(title)
                    
                    val episode = Episode(
                        data = href,
                        name = title,
                        episode = episodeNumber,
                        posterUrl = posterUrl,
                        description = episodeDate?.let { "Episode aired on $it" }
                    )
                    
                    seriesMap.getOrPut(seriesName) { mutableListOf() }.add(episode)
                }
            }
        }
        
        // Method 2: Direct link parsing for series pages
        if (seriesMap.isEmpty()) {
            val seriesLinks = document.select("a[href*='${request.data}']").filter { element ->
                val href = element.attr("href")
                val text = element.text().trim()
                
                href.isNotEmpty() && 
                text.isNotEmpty() && 
                href.startsWith(mainUrl) &&
                (href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) || href.contains("serial")) &&
                text.length > 5 &&
                !processedUrls.contains(href)
            }
            
            seriesLinks.forEach { element ->
                val href = element.attr("href")
                val title = element.text().trim()
                val img = element.selectFirst("img") ?: element.parent()?.selectFirst("img")
                val originalPosterUrl = img?.attr("src") ?: img?.attr("data-src")
                val posterUrl = generateLandscapeThumbnail(originalPosterUrl)
                
                if (!processedUrls.contains(href)) {
                    processedUrls.add(href)
                    val (seriesName, episodeNumber, episodeDate) = extractEpisodeInfo(title)
                    
                    val episode = Episode(
                        data = href,
                        name = title,
                        episode = episodeNumber,
                        posterUrl = posterUrl,
                        description = episodeDate?.let { "Episode aired on $it" }
                    )
                    
                    seriesMap.getOrPut(seriesName) { mutableListOf() }.add(episode)
                }
            }
        }
        
        // Convert series map to search responses with enhanced sorting
        val seriesResults = seriesMap.map { (seriesName, episodes) ->
            // Sort episodes by date first, then by episode number
            val sortedEpisodes = episodes.sortedWith(compareBy(
                { episode ->
                    episode.description?.let { desc ->
                        val dateMatch = Regex("(\\d{2}-\\d{2}-\\d{4})").find(desc)
                        dateMatch?.value?.let { dateStr ->
                            try {
                                SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).parse(dateStr)?.time ?: 0L
                            } catch (e: Exception) { 0L }
                        } ?: 0L
                    } ?: 0L
                },
                { it.episode ?: 0 },
                { it.name }
            )).reversed() // Most recent first
            
            val seriesPoster = sortedEpisodes.firstOrNull()?.posterUrl
            val seriesUrl = "$mainUrl/series/${seriesName.replace(" ", "-").lowercase()}"
            
            newTvSeriesSearchResponse(seriesName, seriesUrl, TvType.TvSeries) {
                this.posterUrl = seriesPoster
            }
        }
        
        return newHomePageResponse(listOf(HomePageList(request.name, seriesResults)), hasNext = false)
    }

    // Enhanced search with better filtering
    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val seriesMap = mutableMapOf<String, MutableList<Episode>>()
        val processedUrls = mutableSetOf<String>()
        
        try {
            val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}", timeout = 15).document
            
            val articles = document.select("article.post, article.regular-post, .post-item, .search-result")
            
            articles.forEach { article ->
                val titleLink = article.selectFirst("h3.entry-title a, .entry-title a, h2 a, h1 a")
                val posterImg = article.selectFirst(".post-thumb img, .entry-image img, img")
                
                if (titleLink != null) {
                    val href = titleLink.attr("href")
                    val title = titleLink.text().trim()
                    val originalPosterUrl = posterImg?.attr("src") ?: posterImg?.attr("data-src")
                    val posterUrl = generateLandscapeThumbnail(originalPosterUrl)
                    
                    if (title.contains(query, ignoreCase = true) && 
                        href.isNotEmpty() && 
                        href.startsWith(mainUrl) &&
                        title.length > 3 &&
                        !processedUrls.contains(href)) {
                        
                        processedUrls.add(href)
                        val (seriesName, episodeNumber, episodeDate) = extractEpisodeInfo(title)
                        
                        val episode = Episode(
                            data = href,
                            name = title,
                            episode = episodeNumber,
                            posterUrl = posterUrl,
                            description = episodeDate?.let { "Episode aired on $it" }
                        )
                        
                        seriesMap.getOrPut(seriesName) { mutableListOf() }.add(episode)
                    }
                }
            }
            
            // Convert to search responses with relevance scoring
            seriesMap.forEach { (seriesName, episodes) ->
                val relevanceScore = when {
                    seriesName.equals(query, ignoreCase = true) -> 3
                    seriesName.contains(query, ignoreCase = true) -> 2
                    else -> 1
                }
                
                if (relevanceScore > 1) {
                    val seriesPoster = episodes.firstOrNull()?.posterUrl
                    val seriesUrl = "$mainUrl/series/${seriesName.replace(" ", "-").lowercase()}"
                    
                    searchResults.add(newTvSeriesSearchResponse(seriesName, seriesUrl, TvType.TvSeries) {
                        this.posterUrl = seriesPoster
                        this.quality = "HD"
                    })
                }
            }
            
        } catch (e: Exception) {
            // Return empty list if search fails
        }
        
        return searchResults.sortedBy { it.name }.take(30)
    }

    // Enhanced load function with better episode fetching
    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/series/")) {
            val seriesName = url.substringAfterLast("/")
                .replace("-", " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }
            
            val episodes = mutableListOf<Episode>()
            
            // Try direct series page first
            val seriesUrl = "$mainUrl/vijay-tv/vijay-tv-serial/${seriesName.replace(" ", "-").lowercase()}/"
            
            try {
                val seriesDoc = app.get(seriesUrl, timeout = 15).document
                val episodeLinks = seriesDoc.select("a[href*='${seriesName.replace(" ", "-").lowercase()}']")
                
                episodeLinks.forEach { link ->
                    val href = link.attr("href")
                    val title = link.text().trim()
                    val img = link.selectFirst("img") ?: link.parent()?.selectFirst("img")
                    val originalPosterUrl = img?.attr("src") ?: img?.attr("data-src")
                    val posterUrl = generateLandscapeThumbnail(originalPosterUrl)
                    
                    if (href.isNotEmpty() && title.isNotEmpty() && href.contains(Regex("\\d{2}-\\d{2}-\\d{4}"))) {
                        val (_, episodeNumber, episodeDate) = extractEpisodeInfo(title)
                        
                        episodes.add(Episode(
                            data = href,
                            name = title,
                            episode = episodeNumber,
                            posterUrl = posterUrl,
                            description = episodeDate?.let { "Episode aired on $it" }
                        ))
                    }
                }
            } catch (e: Exception) {
                // Fallback to main page search
                val mainPageUrls = listOf(
                    "$mainUrl/vijay-tv/vijay-tv-serial/",
                    "$mainUrl/sun-tv/sun-tv-serial/",
                    "$mainUrl/zee-tamil/zee-tamil-serial/",
                    "$mainUrl/colors-tamil/colors-tamil-serial/",
                    "$mainUrl/kalaignar-tv/kalaignar-tv-serial/"
                )
                
                mainPageUrls.forEach { pageUrl ->
                    try {
                        val document = app.get(pageUrl, timeout = 10).document
                        val articles = document.select("article.post, article.regular-post")
                        
                        articles.forEach { article ->
                            val titleLink = article.selectFirst("h3.entry-title a, .entry-title a")
                            val posterImg = article.selectFirst(".post-thumb img, img")
                            
                            if (titleLink != null) {
                                val href = titleLink.attr("href")
                                val title = titleLink.text().trim()
                                val originalPosterUrl = posterImg?.attr("src")
                                val posterUrl = generateLandscapeThumbnail(originalPosterUrl)
                                
                                val (extractedSeriesName, episodeNumber, episodeDate) = extractEpisodeInfo(title)
                                
                                if (extractedSeriesName.equals(seriesName, ignoreCase = true)) {
                                    episodes.add(Episode(
                                        data = href,
                                        name = title,
                                        episode = episodeNumber,
                                        posterUrl = posterUrl,
                                        description = episodeDate?.let { "Episode aired on $it" }
                                    ))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Continue with other pages
                    }
                }
            }
            
            // Enhanced episode sorting
            val sortedEpisodes = episodes.sortedWith(compareBy(
                { episode ->
                    episode.description?.let { desc ->
                        val dateMatch = Regex("(\\d{2}-\\d{2}-\\d{4})").find(desc)
                        dateMatch?.value?.let { dateStr ->
                            try {
                                SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).parse(dateStr)?.time ?: 0L
                            } catch (e: Exception) { 0L }
                        } ?: 0L
                    } ?: 0L
                },
                { it.episode ?: 0 },
                { it.name }
            )).reversed()
            
            return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = sortedEpisodes.firstOrNull()?.posterUrl
                this.plot = "Tamil TV Serial - $seriesName\n\nWatch latest episodes with landscape thumbnails for better viewing experience."
                this.year = Calendar.getInstance().get(Calendar.YEAR)
            }
        } else {
            // Handle direct episode URL
            val document = app.get(url, timeout = 15).document
            
            val title = document.selectFirst("title")?.text()?.let { titleText ->
                titleText.substringBefore(" - TamilDhool")
                    .substringBefore(" - Tamil")
                    .substringBefore(" Online")
                    .trim()
            } ?: document.selectFirst("h1, h2, h3")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.titlecase() }
            
            val originalPoster = document.selectFirst("img[src*='tamildhool'], meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img[src*='tamildhool']")?.attr("src")
                ?: document.selectFirst("img")?.attr("src")
                
            val poster = generateLandscapeThumbnail(originalPoster)
            
            val description = document.selectFirst("meta[name='description']")?.attr("content")
                ?: document.selectFirst("meta[property='og:description']")?.attr("content")
                ?: document.selectFirst("p")?.text()
                ?: "Tamil serial episode"
            
            val (seriesName, episodeNumber, episodeDate) = extractEpisodeInfo(title)
            
            return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, listOf(
                Episode(
                    data = url,
                    name = title,
                    episode = episodeNumber,
                    posterUrl = poster,
                    description = episodeDate?.let { "Episode aired on $it" }
                )
            )) {
                this.posterUrl = poster
                this.plot = description
                this.year = Calendar.getInstance().get(Calendar.YEAR)
            }
        }
    }

    // Enhanced link loading with better extractor support
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        try {
            val document = app.get(data, timeout = 15).document
            
            // Method 1: Enhanced TamilBliss link extraction
            val tamilBlissLinks = document.select("a[href*='tamilbliss.com'], a[href*='tamilbliss']")
            tamilBlissLinks.forEach { link ->
                val href = link.attr("href")
                val videoIdMatch = Regex("video=([a-zA-Z0-9]+)").find(href)
                if (videoIdMatch != null) {
                    val videoId = videoIdMatch.groups[1]?.value
                    if (videoId != null && videoId.length >= 6) {
                        loadExtractor("https://www.dailymotion.com/embed/video/$videoId", subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
            
            // Method 2: Enhanced Dailymotion extraction
            val dailymotionPatterns = listOf(
                "img[src*='dailymotion.com']",
                "a[href*='dailymotion.com']",
                "iframe[src*='dailymotion.com']"
            )
            
            dailymotionPatterns.forEach { pattern ->
                document.select(pattern).forEach { element ->
                    val src = element.attr("src").ifEmpty { element.attr("href") }
                    val videoIdMatch = Regex("video/([a-zA-Z0-9]+)").find(src)
                    if (videoIdMatch != null) {
                        val videoId = videoIdMatch.groups[1]?.value
                        if (videoId != null && videoId.length >= 6) {
                            loadExtractor("https://www.dailymotion.com/embed/video/$videoId", subtitleCallback, callback)
                            foundLinks = true
                        }
                    }
                }
            }
            
            // Method 3: Enhanced iframe extraction
            val iframes = document.select("iframe[src], iframe[data-src]")
            iframes.forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isNotEmpty()) {
                    val fullUrl = when {
                        src.startsWith("//") -> "https:$src"
                        src.startsWith("/") -> "$mainUrl$src"
                        else -> src
                    }
                    
                    if (fullUrl.contains("dailymotion") || fullUrl.contains("youtube") || 
                        fullUrl.contains("vimeo") || fullUrl.contains("player")) {
                        loadExtractor(fullUrl, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
            
            // Method 4: Enhanced pattern matching in HTML
            val htmlContent = document.html()
            val enhancedVideoIdPatterns = listOf(
                Regex("video[=:]([a-zA-Z0-9_-]{6,})"),
                Regex("embed/video/([a-zA-Z0-9_-]{6,})"),
                Regex("dai\\.ly/([a-zA-Z0-9_-]{6,})"),
                Regex("dailymotion\\.com/(?:embed/)?video/([a-zA-Z0-9_-]{6,})"),
                Regex("youtube\\.com/(?:embed/|watch\\?v=)([a-zA-Z0-9_-]{11})"),
                Regex("youtu\\.be/([a-zA-Z0-9_-]{11})"),
                Regex("vimeo\\.com/(?:video/)?([0-9]{6,})")
            )
            
            enhancedVideoIdPatterns.forEach { pattern ->
                val matches = pattern.findAll(htmlContent)
                matches.forEach { match ->
                    val videoId = match.groups[1]?.value
                    if (videoId != null && videoId.length >= 6) {
                        val embedUrl = when {
                            pattern.pattern.contains("youtube") -> "https://www.youtube.com/embed/$videoId"
                            pattern.pattern.contains("vimeo") -> "https://player.vimeo.com/video/$videoId"
                            else -> "https://www.dailymotion.com/embed/video/$videoId"
                        }
                        loadExtractor(embedUrl, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
            
            // Method 5: Direct video file extraction
            val videoElements = document.select("video source[src], a[href*='.mp4'], a[href*='.m3u8'], a[href*='.mkv']")
            videoElements.forEach { element ->
                val src = element.attr("src").ifEmpty { element.attr("href") }
                if (src.isNotEmpty()) {
                    val fullUrl = when {
                        src.startsWith("//") -> "https:$src"
                        src.startsWith("/") -> "$mainUrl$src"
                        else -> src
                    }
                    loadExtractor(fullUrl, subtitleCallback, callback)
                    foundLinks = true
                }
            }
            
        } catch (e: Exception) {
            return false
        }
        
        return foundLinks
    }
}
