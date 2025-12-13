package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TamilDhoolProvider : MainAPI() {
    override var mainUrl = "https://www.tamildhool.tech"
    override var name = "TamilDhool"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "ta"
    override val hasMainPage = true

    private data class PageData(val name: String, val url: String)
    
    private val mainPages = listOf(
        PageData("Vijay TV Serial", "$mainUrl/vijay-tv/vijay-tv-serial/"),
        PageData("Vijay TV Show", "$mainUrl/vijay-tv/vijay-tv-show/"),
        PageData("Zee Tamil Serial", "$mainUrl/zee-tamil/zee-tamil-serial/"),
        PageData("Zee Tamil Show", "$mainUrl/zee-tamil/zee-tamil-show/"),
        PageData("Sun TV Serial", "$mainUrl/sun-tv/sun-tv-serial/"),
        PageData("Sun TV Show", "$mainUrl/sun-tv/sun-tv-show/"),
        PageData("Kalaignar TV", "$mainUrl/kalaignar-tv/")
    )

    override val mainPage = mainPages.map {
        MainPageData(it.name, it.url, false)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + if (page > 1) "page/$page/" else "").document
        
        val episodes = doc.select("article.regular-post").mapNotNull { elem ->
            elem.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = episodes,
                isHorizontalImages = false
            ),
            hasNext = doc.selectFirst("a.next.page-numbers") != null
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.entry-title a")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("h3.entry-title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("div.post-thumb img")?.attr("data-src-webp") 
                ?: this.selectFirst("div.post-thumb img")?.attr("src")
        )

        return newTvSeriesSearchResponse(
            name = title,
            url = href,
            type = TvType.TvSeries
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        
        return doc.select("article.regular-post").mapNotNull { elem ->
            elem.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1.entry-title")?.text() ?: "Unknown"
        val poster = doc.selectFirst("div.entry-cover")?.attr("style")?.let {
            Regex("url\\('([^']+)'\\)").find(it)?.groupValues?.get(1)
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        
        val description = doc.selectFirst("div.entry-content p")?.text()
        
        // Extract video sources from the page
        val episodes = mutableListOf<Episode>()
        
        // Look for video links in the content
        doc.select("figure.td-featured-thumb").forEachIndexed { index, elem ->
            val episodeUrl = elem.selectFirst("a[href*=tamilbliss]")?.attr("href") ?: return@forEachIndexed
            val sourceLabel = elem.selectFirst("div.td-source-label")?.text() ?: "Source ${index + 1}"
            
            episodes.add(
                Episode(
                    data = episodeUrl,
                    name = "$title - $sourceLabel",
                    episode = index + 1
                )
            )
        }
        
        // If no episodes found, add the page itself as an episode
        if (episodes.isEmpty()) {
            episodes.add(
                Episode(
                    data = url,
                    name = title,
                    episode = 1
                )
            )
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
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
        // If it's a tamilbliss link, extract video parameter
        if (data.contains("tamilbliss.com")) {
            val videoId = Regex("video=([^&]+)").find(data)?.groupValues?.get(1) ?: return false
            
            // Check if it's Dailymotion (starts with 'k' and very long)
            if (videoId.startsWith("k") && videoId.length > 10) {
                loadExtractor(
                    "https://www.dailymotion.com/embed/video/$videoId",
                    subtitleCallback,
                    callback
                )
            } else {
                // JW Player - follow redirect chain to get Thrfive embed
                try {
                    // Step 1: Get tamilbliss page with referer
                    val tamilblissResponse = app.get(
                        data,
                        referer = mainUrl,
                        allowRedirects = true
                    )
                    
                    // Step 2: Get the final redirected page (startuphappy or similar)
                    val playerDoc = tamilblissResponse.document
                    
                    // Step 3: Extract iframe embed URL (thrfive.io)
                    val embedUrl = playerDoc.selectFirst("iframe[src*=thrfive]")?.attr("src")
                        ?: playerDoc.selectFirst("iframe[src*=embed]")?.attr("src")
                    
                    if (embedUrl != null) {
                        // Extract Thrfive video
                        extractThrfive(embedUrl, callback)
                    }
                } catch (e: Exception) {
                    // Fallback: construct thrfive URL directly
                    extractThrfive("https://thrfive.io/embed/$videoId", callback)
                }
            }
        } else {
            // Load the episode page and extract video links recursively
            val doc = app.get(data).document
            
            doc.select("figure.td-featured-thumb").forEach { elem ->
                val episodeUrl = elem.selectFirst("a[href*=tamilbliss]")?.attr("href") ?: return@forEach
                
                // Recursively call loadLinks with the extracted URL
                loadLinks(episodeUrl, isCasting, subtitleCallback, callback)
            }
        }
        
        return true
    }
    
    private suspend fun extractThrfive(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Load the thrfive embed page
            val embedDoc = app.get(
                embedUrl,
                referer = "https://tamilbliss.com/"
            ).document
            
            // Extract the full HTML including scripts
            val html = embedDoc.html()
            
            // Method 1: Look for direct m3u8 URL in scripts/HTML
            val m3u8Regex = Regex("""https://coke\.infamous\.network/stream/[^\s"'\\]+\.m3u8""")
            val m3u8Match = m3u8Regex.find(html)
            
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.value
                
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Thrfive - HLS",
                        url = m3u8Url,
                        referer = "https://tamilbliss.com/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            } else {
                // Method 2: Generic stream URL pattern
                val genericM3u8Regex = Regex("""https://[^/]+/stream/[^\s"'\\]+\.m3u8""")
                val genericMatch = genericM3u8Regex.find(html)
                
                if (genericMatch != null) {
                    val m3u8Url = genericMatch.value
                    
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Thrfive - HLS",
                            url = m3u8Url,
                            referer = "https://tamilbliss.com/",
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Silent fail - video extraction failed
        }
    }
}
