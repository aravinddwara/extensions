package recloudstream

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class MediaDeliveryExtractor : ExtractorApi() {
    override val name = "MediaDelivery"
    override val mainUrl = "https://iframe.mediadelivery.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Step 1: Follow tamilbliss redirect to get intermediate website
            val tamilblissResponse = app.get(
                url,
                referer = referer,
                allowRedirects = false,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
                )
            )
            
            val redirectLocation = tamilblissResponse.headers["location"] 
                ?: tamilblissResponse.headers["Location"]
                ?: return
            
            // Step 2: Get the intermediate page (probizbeacon, walletcanvas, etc.)
            val intermediateResponse = app.get(
                redirectLocation,
                referer = url,
                allowRedirects = true,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
                )
            )
            
            val intermediateDoc = intermediateResponse.document
            val embedUrl = extractMediaDeliveryEmbedUrl(intermediateDoc) ?: return
            
            // Step 3: Access the embed page with intermediate site as referer
            val embedResponse = app.get(
                embedUrl,
                referer = intermediateResponse.url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                    "Accept-Language" to "en-GB,en;q=0.6",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site"
                )
            )
            
            val embedDoc = embedResponse.document
            
            // Step 4: Extract video sources from embed page
            extractVideoSourcesFromMediaDelivery(embedDoc, intermediateResponse.url, callback)
            
        } catch (e: Exception) {
            // Silent fail - let other extractors try
        }
    }
    
    private fun extractMediaDeliveryEmbedUrl(doc: Document): String? {
        // Look for iframe.mediadelivery.net embed URLs
        val iframes = doc.select("iframe[src*='iframe.mediadelivery.net']")
        if (iframes.isNotEmpty()) {
            return iframes.first().attr("src")
        }
        
        // Look for JavaScript variables containing the embed URL
        val scripts = doc.select("script")
        for (script in scripts) {
            val content = script.html()
            val embedMatch = Regex("iframe\\.mediadelivery\\.net/embed/([^\"'\\s]+)").find(content)
            if (embedMatch != null) {
                val embedPath = embedMatch.groupValues[1]
                return "https://iframe.mediadelivery.net/embed/$embedPath"
            }
        }
        
        return null
    }
    
    private suspend fun extractVideoSourcesFromMediaDelivery(
        doc: Document,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val scripts = doc.select("script")
        
        // Method 1: Look for direct m3u8 URLs in script content
        for (script in scripts) {
            val content = script.html()
            
            // Look for vz-*.b-cdn.net URLs (BunnyCDN pattern)
            val cdnUrlMatches = Regex("https://vz-[^\"'\\s]+\\.b-cdn\\.net/[^\"'\\s]+/playlist\\.m3u8").findAll(content)
            for (match in cdnUrlMatches) {
                val masterUrl = match.value
                parseM3u8Playlist(masterUrl, referer, callback)
                return
            }
            
            // Look for any m3u8 playlist URLs
            val m3u8Matches = Regex("https://[^\"'\\s]+playlist\\.m3u8[^\"'\\s]*").findAll(content)
            for (match in m3u8Matches) {
                val masterUrl = match.value
                parseM3u8Playlist(masterUrl, referer, callback)
                return
            }
            
            // Look for video configuration objects
            val configMatch = Regex("(?:src|source|file|url)\\s*[:\\=]\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']").find(content)
            if (configMatch != null) {
                val videoUrl = configMatch.groupValues[1]
                parseM3u8Playlist(videoUrl, referer, callback)
                return
            }
        }
        
        // Method 2: Look for video elements in the DOM
        val videoElements = doc.select("video source[src], video[src]")
        for (element in videoElements) {
            val src = element.attr("src")
            if (src.contains(".m3u8")) {
                parseM3u8Playlist(src, referer, callback)
                return
            }
        }
        
        // Method 3: Try to construct playlist URL from embed URL pattern
        val currentUrl = doc.baseUri()
        val embedIdMatch = Regex("embed/\\d+/([^/?]+)").find(currentUrl)
        if (embedIdMatch != null) {
            val videoId = embedIdMatch.groupValues[1]
            
            // Try common CDN patterns for this service
            val cdnPatterns = listOf(
                "https://vz-8cf4325c-bc5.b-cdn.net/$videoId/playlist.m3u8",
                "https://customer-8cf4325c-bc5.b-cdn.net/$videoId/playlist.m3u8"
            )
            
            for (pattern in cdnPatterns) {
                try {
                    // Test if the URL exists
                    val testResponse = app.get(
                        pattern,
                        referer = referer,
                        headers = mapOf(
                            "Accept" to "*/*",
                            "Origin" to "https://iframe.mediadelivery.net"
                        )
                    )
                    
                    if (testResponse.isSuccessful) {
                        parseM3u8Playlist(pattern, referer, callback)
                        return
                    }
                } catch (e: Exception) {
                    // Try next pattern
                    continue
                }
            }
        }
        
        // Method 4: Look for API endpoints that might provide video data
        for (script in scripts) {
            val content = script.html()
            
            val apiPatterns = listOf(
                Regex("fetch\\([\"']([^\"']+api[^\"']*)[\"']"),
                Regex("xhr\\.open\\([\"']GET[\"'],\\s*[\"']([^\"']+)[\"']"),
                Regex("ajax\\([^}]*url\\s*:\\s*[\"']([^\"']+)[\"']")
            )
            
            for (pattern in apiPatterns) {
                val match = pattern.find(content)
                if (match != null) {
                    val apiUrl = match.groupValues[1]
                    try {
                        val fullApiUrl = if (apiUrl.startsWith("http")) {
                            apiUrl
                        } else {
                            "https://iframe.mediadelivery.net$apiUrl"
                        }
                        
                        val apiResponse = app.get(
                            fullApiUrl,
                            referer = referer,
                            headers = mapOf(
                                "Accept" to "application/json, text/plain, */*",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin" to "https://iframe.mediadelivery.net"
                            )
                        )
                        
                        val responseText = apiResponse.text
                        val urlMatch = Regex("[\"']([^\"']*\\.m3u8[^\"']*)[\"']").find(responseText)
                        if (urlMatch != null) {
                            parseM3u8Playlist(urlMatch.groupValues[1], referer, callback)
                            return
                        }
                    } catch (e: Exception) {
                        // Continue with other methods
                    }
                }
            }
        }
    }
    
    private suspend fun parseM3u8Playlist(
        masterUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val playlistResponse = app.get(
                masterUrl,
                referer = referer,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Accept-Encoding" to "gzip, deflate, br, zstd",
                    "Accept-Language" to "en-GB,en;q=0.6",
                    "Origin" to "https://iframe.mediadelivery.net",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
                )
            )
            
            val playlistContent = playlistResponse.text
            
            // Parse master playlist for different quality variants
            val lines = playlistContent.split("\n")
            var currentQuality = Qualities.Unknown.value
            var currentLabel = ""
            
            for (i in lines.indices) {
                val line = lines[i].trim()
                
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    // Extract resolution/quality info
                    val resolutionMatch = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                    if (resolutionMatch != null) {
                        val width = resolutionMatch.groupValues[1].toInt()
                        val height = resolutionMatch.groupValues[2].toInt()
                        currentQuality = when {
                            height >= 1080 -> Qualities.P1080.value
                            height >= 720 -> Qualities.P720.value
                            height >= 480 -> Qualities.P480.value
                            height >= 360 -> Qualities.P360.value
                            height >= 240 -> Qualities.P240.value
                            else -> Qualities.Unknown.value
                        }
                        currentLabel = "${height}p"
                    }
                    
                    // Extract bandwidth info if no resolution
                    if (currentQuality == Qualities.Unknown.value) {
                        val bandwidthMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                        if (bandwidthMatch != null) {
                            val bandwidth = bandwidthMatch.groupValues[1].toInt()
                            currentQuality = when {
                                bandwidth > 2000000 -> Qualities.P720.value
                                bandwidth > 1000000 -> Qualities.P480.value
                                bandwidth > 500000 -> Qualities.P360.value
                                else -> Qualities.P240.value
                            }
                        }
                    }
                } else if (line.isNotEmpty() && !line.startsWith("#") && line.contains(".m3u8")) {
                    // This is a variant URL
                    val variantUrl = if (line.startsWith("http")) {
                        line
                    } else {
                        // Relative URL, construct full URL
                        val baseUrl = masterUrl.substringBeforeLast("/")
                        "$baseUrl/$line"
                    }
                    
                    val qualityName = if (currentLabel.isNotEmpty()) {
                        "$name $currentLabel"
                    } else {
                        name
                    }
                    
                    callback.invoke(
                        ExtractorLink(
                            qualityName,
                            qualityName,
                            variantUrl,
                            referer,
                            currentQuality,
                            true
                        )
                    )
                    
                    // Reset for next variant
                    currentQuality = Qualities.Unknown.value
                    currentLabel = ""
                }
            }
            
            // If no variants found, use master playlist directly
            if (!playlistContent.contains("#EXT-X-STREAM-INF:")) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        masterUrl,
                        referer,
                        Qualities.Unknown.value,
                        true
                    )
                )
            }
            
        } catch (e: Exception) {
            // Fallback: return the URL as-is
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    masterUrl,
                    referer,
                    Qualities.Unknown.value,
                    true
                )
            )
        }
    }
}
