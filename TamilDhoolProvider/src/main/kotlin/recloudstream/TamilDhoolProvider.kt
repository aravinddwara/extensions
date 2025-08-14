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

    // Predefined series data
    private val vijayTVSerials = listOf(
        Pair("Chinna Marumagal", "$mainUrl/vijay-tv/vijay-tv-serial/chinna-marumagal/"),
        Pair("Siragadikka Aasai", "$mainUrl/vijay-tv/vijay-tv-serial/siragadikka-aasai/"),
        Pair("Ayyanar Thunai", "$mainUrl/vijay-tv/vijay-tv-serial/ayyanar-thunai/"),
        Pair("Pandian Stores S-2", "$mainUrl/vijay-tv/vijay-tv-serial/pandian-stores-s-2/"),
        Pair("Sakthivel", "$mainUrl/vijay-tv/vijay-tv-serial/sakthivel/"),
        Pair("Magale En Marumagale", "$mainUrl/vijay-tv/vijay-tv-serial/magale-en-marumagale/"),
        Pair("Sindhu Bairavi Kacheri Arambam", "$mainUrl/vijay-tv/vijay-tv-serial/sindhu-bairavi-kacheri-arambam/"),
        Pair("Mahanadhi", "$mainUrl/vijay-tv/vijay-tv-serial/mahanadhi/"),
        Pair("Poongatru Thirumbuma", "$mainUrl/vijay-tv/vijay-tv-serial/poongatru-thirumbuma/"),
        Pair("Aaha Kalyanam", "$mainUrl/vijay-tv/vijay-tv-serial/aaha-kalyanam/"),
        Pair("Dhanam", "$mainUrl/vijay-tv/vijay-tv-serial/dhanam/"),
        Pair("Thendrale Mella Pesu", "$mainUrl/vijay-tv/vijay-tv-serial/thendrale-mella-pesu/"),
        Pair("Kanmani Anbudan", "$mainUrl/vijay-tv/vijay-tv-serial/kanmani-anbudan/")
    )

    private val sunTVSerials = listOf(
        Pair("Singappenne", "$mainUrl/sun-tv/sun-tv-serial/singappenne/"),
        // Add more Sun TV serials here as needed
    )

    private val zeeTamilSerials = listOf(
        // Add Zee Tamil serials here as needed
    )

    private val colorsTamilSerials = listOf(
        // Add Colors Tamil serials here as needed
    )

    private val kalaignarTVSerials = listOf(
        // Add Kalaignar TV serials here as needed
    )

    override val mainPage = mainPageOf(
        "vijay-tv-serials" to "Vijay TV Serials",
        "sun-tv-serials" to "Sun TV Serials",
        "zee-tamil-serials" to "Zee Tamil Serials",
        "colors-tamil-serials" to "Colors Tamil Serials",
        "kalaignar-tv-serials" to "Kalaignar TV Serials"
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

    // Helper function to extract date from episode title or URL
    private fun extractDateFromText(text: String): String? {
        val datePattern = Regex("(\\d{1,2}-\\d{1,2}-\\d{4})")
        return datePattern.find(text)?.groups?.get(1)?.value
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val seriesList = mutableListOf<SearchResponse>()
        
        when (request.data) {
            "vijay-tv-serials" -> {
                vijayTVSerials.forEach { series ->
                    val title = series.first
                    val url = series.second
                    seriesList.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        // You can add poster URLs here if available
                    })
                }
            }
            "sun-tv-serials" -> {
                sunTVSerials.forEach { series ->
                    val title = series.first
                    val url = series.second
                    seriesList.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        // You can add poster URLs here if available
                    })
                }
            }
            "zee-tamil-serials" -> {
                zeeTamilSerials.forEach { series ->
                    val title = series.first
                    val url = series.second
                    seriesList.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        // You can add poster URLs here if available
                    })
                }
            }
            "colors-tamil-serials" -> {
                colorsTamilSerials.forEach { series ->
                    val title = series.first
                    val url = series.second
                    seriesList.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        // You can add poster URLs here if available
                    })
                }
            }
            "kalaignar-tv-serials" -> {
                kalaignarTVSerials.forEach { series ->
                    val title = series.first
                    val url = series.second
                    seriesList.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        // You can add poster URLs here if available
                    })
                }
            }
        }
        
        return newHomePageResponse(listOf(HomePageList(request.name, seriesList)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        
        // Search through all predefined series
        val allSeries = vijayTVSerials + sunTVSerials + zeeTamilSerials + colorsTamilSerials + kalaignarTVSerials
        
        allSeries.forEach { series ->
            val title = series.first
            val url = series.second
            if (title.contains(query, ignoreCase = true)) {
                searchResults.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries))
            }
        }
        
        return searchResults.take(50)
    }

    override suspend fun load(url: String): LoadResponse? {
        // The URL is the series main page (e.g., .../siragadikka-aasai/)
        val document = app.get(url).document
        val allEpisodes = mutableListOf<Episode>()
        
        // Extract series title from URL path
        val seriesTitle = url.substringAfterLast("/").substringBeforeLast("/")
            .replace("-", " ")
            .split(" ")
            .joinToString(" ") { word -> 
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        
        // Extract poster - look for any image on the page
        val poster = document.selectFirst("img")?.attr("src")
        
        // Description
        val description = "Tamil serial episodes"
        
        // Find all episode links on this series page
        val episodeLinks = document.select("a[href]").filter { element ->
            val href = element.attr("href")
            val text = element.text().trim()
            
            href.isNotEmpty() && 
            text.isNotEmpty() && 
            href.startsWith(url) && // Episode URLs start with series URL
            href.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) && // Contains date pattern
            text.contains(Regex("\\d{2}-\\d{2}-\\d{4}")) // Title also contains date
        }
        
        // Process episodes
        val processedUrls = mutableSetOf<String>()
        episodeLinks.forEach { element ->
            val href = element.attr("href")
            val title = element.text().trim()
            
            if (!processedUrls.contains(href)) {
                processedUrls.add(href)
                
                // Extract date from title or URL for episode name
                val dateString = extractDateFromText(title) ?: extractDateFromText(href)
                val episodeName = dateString ?: "Episode ${allEpisodes.size + 1}"
                
                // Get poster from element or use series poster
                val episodePoster = element.selectFirst("img")?.attr("src") ?: poster
                
                allEpisodes.add(
                    Episode(
                        data = href,
                        name = episodeName, // Just the date
                        episode = null, // Will be set after sorting
                        posterUrl = episodePoster,
                        description = "Episode aired on $episodeName"
                    )
                )
            }
        }
        
        // Sort episodes by date (newest first)
        allEpisodes.sortByDescending { episode ->
            val dateString = episode.name
            if (dateString != null && dateString.matches(Regex("\\d{1,2}-\\d{1,2}-\\d{4}"))) {
                parseDate(dateString)
            } else {
                0L
            }
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
