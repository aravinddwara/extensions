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
    data class SeriesInfo(val title: String, val url: String, val posterUrl: String? = null)

    private val vijayTVSerials = listOf(
        SeriesInfo("Chinna Marumagal", "$mainUrl/vijay-tv/vijay-tv-serial/chinna-marumagal/", "https://i.imgur.com/placeholder1.jpg"),
        SeriesInfo("Siragadikka Aasai", "$mainUrl/vijay-tv/vijay-tv-serial/siragadikka-aasai/", "https://i.imgur.com/placeholder2.jpg"),
        SeriesInfo("Ayyanar Thunai", "$mainUrl/vijay-tv/vijay-tv-serial/ayyanar-thunai/", "https://i.imgur.com/placeholder3.jpg"),
        SeriesInfo("Pandian Stores S-2", "$mainUrl/vijay-tv/vijay-tv-serial/pandian-stores-s-2/", "https://i.imgur.com/placeholder4.jpg"),
        SeriesInfo("Sakthivel", "$mainUrl/vijay-tv/vijay-tv-serial/sakthivel/", "https://i.imgur.com/placeholder5.jpg"),
        SeriesInfo("Magale En Marumagale", "$mainUrl/vijay-tv/vijay-tv-serial/magale-en-marumagale/", "https://i.imgur.com/placeholder6.jpg"),
        SeriesInfo("Sindhu Bairavi Kacheri Arambam", "$mainUrl/vijay-tv/vijay-tv-serial/sindhu-bairavi-kacheri-arambam/", "https://i.imgur.com/placeholder7.jpg"),
        SeriesInfo("Mahanadhi", "$mainUrl/vijay-tv/vijay-tv-serial/mahanadhi/", "https://i.imgur.com/placeholder8.jpg"),
        SeriesInfo("Poongatru Thirumbuma", "$mainUrl/vijay-tv/vijay-tv-serial/poongatru-thirumbuma/", "https://i.imgur.com/placeholder9.jpg"),
        SeriesInfo("Aaha Kalyanam", "$mainUrl/vijay-tv/vijay-tv-serial/aaha-kalyanam/", "https://i.imgur.com/placeholder10.jpg"),
        SeriesInfo("Dhanam", "$mainUrl/vijay-tv/vijay-tv-serial/dhanam/", "https://i.imgur.com/placeholder11.jpg"),
        SeriesInfo("Thendrale Mella Pesu", "$mainUrl/vijay-tv/vijay-tv-serial/thendrale-mella-pesu/", "https://i.imgur.com/placeholder12.jpg"),
        SeriesInfo("Kanmani Anbudan", "$mainUrl/vijay-tv/vijay-tv-serial/kanmani-anbudan/", "https://i.imgur.com/placeholder13.jpg")
    )

    private val sunTVSerials = listOf(
        SeriesInfo("Singappenne", "$mainUrl/sun-tv/sun-tv-serial/singappenne/", "https://i.imgur.com/placeholder14.jpg")
        // Add more Sun TV serials here as needed
    )

    private val zeeTamilSerials = listOf<SeriesInfo>(
        // Add Zee Tamil serials here as needed
    )

    private val colorsTamilSerials = listOf<SeriesInfo>(
        // Add Colors Tamil serials here as needed
    )

    private val kalaignarTVSerials = listOf<SeriesInfo>(
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
                for (series in vijayTVSerials) {
                    seriesList.add(newTvSeriesSearchResponse(series.title, series.url, TvType.TvSeries) {
                        this.posterUrl = series.posterUrl
                    })
                }
            }
            "sun-tv-serials" -> {
                for (series in sunTVSerials) {
                    seriesList.add(newTvSeriesSearchResponse(series.title, series.url, TvType.TvSeries) {
                        this.posterUrl = series.posterUrl
                    })
                }
            }
            "zee-tamil-serials" -> {
                for (series in zeeTamilSerials) {
                    seriesList.add(newTvSeriesSearchResponse(series.title, series.url, TvType.TvSeries) {
                        this.posterUrl = series.posterUrl
                    })
                }
            }
            "colors-tamil-serials" -> {
                for (series in colorsTamilSerials) {
                    seriesList.add(newTvSeriesSearchResponse(series.title, series.url, TvType.TvSeries) {
                        this.posterUrl = series.posterUrl
                    })
                }
            }
            "kalaignar-tv-serials" -> {
                for (series in kalaignarTVSerials) {
                    seriesList.add(newTvSeriesSearchResponse(series.title, series.url, TvType.TvSeries) {
                        this.posterUrl = series.posterUrl
                    })
                }
            }
        }
        
        return newHomePageResponse(listOf(HomePageList(request.name, seriesList)), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        
        // Search through all predefined series
        val allSeries = mutableListOf<SeriesInfo>()
        allSeries.addAll(vijayTVSerials)
        allSeries.addAll(sunTVSerials)
        allSeries.addAll(zeeTamilSerials)
        allSeries.addAll(colorsTamilSerials)
        allSeries.addAll(kalaignarTVSerials)
        
        for (series in allSeries) {
            if (series.title.contains(query, ignoreCase = true)) {
                searchResults.add(newTvSeriesSearchResponse(series.title, series.url, TvType.TvSeries))
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
        for (element in episodeLinks) {
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
        for (i in allEpisodes.indices) {
            allEpisodes[i].episode = i + 1
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
            for (link in tamilBlissLinks) {
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
            for (img in dailymotionThumbnails) {
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
            for (iframe in iframes) {
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
            
            for (pattern in videoIdPatterns) {
                val matches = pattern.findAll(htmlContent)
                for (match in matches) {
                    val videoId = match.groups[1]?.value
                    if (videoId != null && videoId.length >= 6) {
                        loadExtractor("https://www.dailymotion.com/embed/video/$videoId", subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            }
            
            // Method 5: Look for direct video links
            val videoElements = document.select("video source[src], a[href*='.mp4'], a[href*='.m3u8']")
            for (element in videoElements) {
                val src = element.attr("src").ifEmpty { element.attr("href") }
                if (src.isNotEmpty()) {
                    loadExtractor(src, subtitleCallback, callback)
                    foundLinks = true
                }
            }
            
            // Method 6: Look for prefetch or preload links
            val prefetchLinks = document.select("link[href*='dai.ly'], link[href*='dailymotion']")
            for (link in prefetchLinks) {
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
