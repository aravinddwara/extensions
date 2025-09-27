package recloudstream

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class ThiraiOneExtractor : ExtractorApi() {
    override val name = "ThiraiOne"
    override val mainUrl = "https://thrfive.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Check if this is a direct thrfive.io embed URL
            if (url.contains("thrfive.io/embed/")) {
                // Direct embed URL - extract video ID and process
                val videoIdMatch = Regex("thrfive\\.io/embed/([a-zA-Z0-9]+)").find(url)
                if (videoIdMatch != null) {
                    val videoId = videoIdMatch.groupValues[1]
                    extractFromThrfiveEmbed(url, videoId, referer, callback)
                    return
                }
            }
            
            // Handle TamilBliss redirect flow
            // Step 1: Get the tamilbliss redirect link with original referer
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
            
            // Step 2: Follow redirect to intermediate page
            val intermediateResponse = app.get(
                redirectLocation,
                referer = url,
                allowRedirects = true,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
                )
            )
            
            val intermediateDoc = intermediateResponse.document
            val embedUrl = extractEmbedUrl(intermediateDoc) ?: return
            
            // Step 3: Extract video ID from embed URL
            val videoIdMatch = Regex("thrfive\\.io/embed/([a-zA-Z0-9]+)").find(embedUrl)
            if (videoIdMatch != null) {
                val videoId = videoIdMatch.groupValues[1]
                extractFromThrfiveEmbed(embedUrl, videoId, intermediateResponse.url, callback)
            }
            
        } catch (e: Exception) {
            // Silent fail - let other extractors try
        }
    }
    
    private suspend fun extractFromThrfiveEmbed(
        embedUrl: String,
        videoId: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Step 1: Get the embed page with proper referer
            val embedResponse = app.get(
                embedUrl,
                referer = referer ?: "https://tamildhool.tech/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                    "Accept-Language" to "en-GB,en;q=0.5",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site",
                    "Sec-GPC" to "1"
                )
            )
            
            val embedDoc = embedResponse.document
            
            // Step 2: Extract video sources from embed page
            extractVideoSources(embedDoc, videoId, embedResponse.url, callback)
            
        } catch (e: Exception) {
            // Try fallback methods
            tryFallbackExtraction(videoId, referer, callback)
        }
    }
    
    private fun extractEmbedUrl(doc: Document): String? {
        // Look for thrfive.io embed URLs in iframes
        val iframes = doc.select("iframe[src*='thrfive.io']")
        if (iframes.isNotEmpty()) {
            return iframes.first()?.attr("src")
        }
        
        // Look for JavaScript variables containing the embed URL
        val scripts = doc.select("script")
        for (script in scripts) {
            val content = script.html()
            val embedMatch = Regex("thrfive\\.io/embed/([a-zA-Z0-9]+)").find(content)
            if (embedMatch != null) {
                val videoId = embedMatch.groupValues[1]
                return "https://thrfive.io/embed/$videoId"
            }
        }
        
        return null
    }
    
    private suspend fun extractVideoSources(
        doc: Document,
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val scripts = doc.select("script")
        
        // Method 1: Look for coke.infamous.network URLs directly
        for (script in scripts) {
            val content = script.html()
            
            // Look for the new CDN URLs
            val cdnUrlMatches = Regex("https://coke\\.infamous\\.network/stream/[^\"'\\s]+\\.m3u8").findAll(content)
            for (match in cdnUrlMatches) {
                val masterUrl = match.value
                parseM3u8Playlist(masterUrl, referer, callback)
                return
            }
            
            // Look for any m3u8 URLs
            val m3u8Matches = Regex("https://[^\"'\\s]+\\.m3u8[^\"'\\s]*").findAll(content)
            for (match in m3u8Matches) {
                val masterUrl = match.value
                parseM3u8Playlist(masterUrl, referer, callback)
                return
            }
        }
        
        // Method 2: Look for JW Player configurations
        for (script in scripts) {
            val content = script.html()
            
            // Look for JW Player setup calls
            val jwSetupMatch = Regex("jwplayer\\([^)]*\\)\\.setup\\(([^;]+)\\)").find(content)
            if (jwSetupMatch != null) {
                val setupContent = jwSetupMatch.groupValues[1]
                extractFromJwPlayerSetup(setupContent, referer, callback)
                return
            }
            
            // Look for sources array in JW Player format
            val sourcesMatch = Regex("sources\\s*:\\s*\\[([^\\]]+)\\]").find(content)
            if (sourcesMatch != null) {
                val sourcesContent = sourcesMatch.groupValues[1]
                extractFromSourcesArray(sourcesContent, referer, callback)
                return
            }
            
            // Look for file property in JavaScript
            val fileMatch = Regex("file\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']").find(content)
            if (fileMatch != null) {
                val fileUrl = fileMatch.groupValues[1]
                parseM3u8Playlist(fileUrl, referer, callback)
                return
            }
        }
        
        // Method 3: Try API endpoints that might provide video data
        val apiEndpoints = listOf(
            "https://thrfive.io/api/source/$videoId",
            "https://thrfive.io/source/$videoId",
            "https://thrfive.io/playlist/$videoId"
        )
        
        for (apiUrl in apiEndpoints) {
            try {
                val apiResponse = app.get(
                    apiUrl,
                    referer = referer,
                    headers = mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to "https://thrfive.io",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
                    )
                )
                
                val responseText = apiResponse.text
                val urlMatch = Regex("[\"']([^\"']*\\.m3u8[^\"']*)[\"']").find(responseText)
                if (urlMatch != null) {
                    parseM3u8Playlist(urlMatch.groupValues[1], referer, callback)
                    return
                }
            } catch (e: Exception) {
                // Continue with next endpoint
            }
        }
        
        // Method 4: Look for video elements (fallback)
        val videoElements = doc.select("video source[src], video[src]")
        for (videoElement in videoElements) {
            val src = videoElement.attr("src")
            if (src.isNotEmpty()) {
                if (src.contains(".m3u8")) {
                    parseM3u8Playlist(src, referer, callback)
                } else {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = src,
                            referer = referer,
                            quality = Qualities.Unknown.value,
                            isM3u8 = false
                        )
                    )
                }
                return
            }
        }
    }
    
    private suspend fun tryFallbackExtraction(
        videoId: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        // Try common patterns for the new CDN infrastructure
        val possibleUrls = listOf(
            "https://coke.infamous.network/stream/$videoId/playlist.m3u8",
            "https://coke.infamous.network/stream/$videoId.m3u8",
            "https://coke.infamous.network/hls/$videoId/playlist.m3u8",
            "https://coke.infamous.network/hls/$videoId.m3u8"
        )
        
        for (testUrl in possibleUrls) {
            try {
                val testResponse = app.get(
                    testUrl,
                    referer = referer ?: "https://thrfive.io/",
                    headers = mapOf(
                        "Accept" to "*/*",
                        "Accept-Encoding" to "gzip, deflate, br, zstd",
                        "Accept-Language" to "en-GB,en;q=0.5",
                        "Origin" to "https://thrfive.io",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "cross-site",
                        "Sec-GPC" to "1",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
                    )
                )
                
                if (testResponse.isSuccessful && testResponse.text.contains("#EXTM3U")) {
                    parseM3u8Playlist(testUrl, referer ?: "https://thrfive.io/", callback)
                    return
                }
            } catch (e: Exception) {
                // Try next URL
                continue
            }
        }
    }
    
    private suspend fun extractFromJwPlayerSetup(setupContent: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Extract file URLs from JW Player setup
            val fileMatch = Regex("file\\s*:\\s*[\"']([^\"']+)[\"']").find(setupContent)
            if (fileMatch != null) {
                val fileUrl = fileMatch.groupValues[1]
                if (fileUrl.contains(".m3u8")) {
                    parseM3u8Playlist(fileUrl, referer, callback)
                    return
                }
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = fileUrl,
                        referer = referer,
                        quality = Qualities.Unknown.value,
                        isM3u8 = fileUrl.contains(".m3u8")
                    )
                )
            }
            
            // Look for playlist URL
            val playlistMatch = Regex("playlist\\s*:\\s*[\"']([^\"']+)[\"']").find(setupContent)
            if (playlistMatch != null) {
                val playlistUrl = playlistMatch.groupValues[1]
                if (playlistUrl.startsWith("http") || playlistUrl.startsWith("/")) {
                    fetchPlaylistData(playlistUrl, referer, callback)
                }
            }
        } catch (e: Exception) {
            // Continue
        }
    }
    
    private suspend fun extractFromSourcesArray(sourcesContent: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Extract all file URLs from sources array
            val fileMatches = Regex("file\\s*:\\s*[\"']([^\"']+)[\"']").findAll(sourcesContent)
            for (match in fileMatches) {
                val fileUrl = match.groupValues[1]
                if (fileUrl.isNotEmpty()) {
                    if (fileUrl.contains(".m3u8")) {
                        parseM3u8Playlist(fileUrl, referer, callback)
                    } else {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = name,
                                url = fileUrl,
                                referer = referer,
                                quality = Qualities.Unknown.value,
                                isM3u8 = false
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Continue
        }
    }
    
    private suspend fun fetchPlaylistData(playlistUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val fullUrl = if (playlistUrl.startsWith("http")) {
                playlistUrl
            } else {
                "https://thrfive.io$playlistUrl"
            }
            
            val response = app.get(
                fullUrl,
                referer = referer,
                headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to "https://thrfive.io",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
                )
            )
            
            val responseText = response.text
            
            // Try to parse and extract video URLs
            val urlMatches = Regex("[\"']([^\"']*(?:\\.m3u8|\\.mp4)[^\"']*)[\"']").findAll(responseText)
            for (match in urlMatches) {
                val videoUrl = match.groupValues[1]
                if (videoUrl.isNotEmpty() && (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4"))) {
                    if (videoUrl.contains(".m3u8")) {
                        parseM3u8Playlist(videoUrl, referer, callback)
                    } else {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = name,
                                url = videoUrl,
                                referer = referer,
                                quality = Qualities.Unknown.value,
                                isM3u8 = false
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Continue
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
                    "Accept-Language" to "en-GB,en;q=0.5",
                    "Origin" to "https://thrfive.io",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "Sec-GPC" to "1",
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
                            source = qualityName,
                            name = qualityName,
                            url = variantUrl,
                            referer = referer,
                            quality = currentQuality,
                            isM3u8 = true
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
                        source = name,
                        name = name,
                        url = masterUrl,
                        referer = referer,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
            
        } catch (e: Exception) {
            // Fallback: return the URL as-is
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = masterUrl,
                    referer = referer,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }
    }
}
