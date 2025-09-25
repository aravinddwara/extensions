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
    ): Boolean {
        try {
            // Step 1: Get the tamilbliss redirect link with original referer
            val tamilblissResponse = app.get(
                url,
                referer = referer,
                allowRedirects = false
            )
            
            val redirectLocation = tamilblissResponse.headers["location"] 
                ?: tamilblissResponse.headers["Location"]
                ?: return false
            
            // Step 2: Follow redirect to thiraisorgam with tamilbliss referer
            val thiraiResponse = app.get(
                redirectLocation,
                referer = url,
                allowRedirects = true
            )
            
            val thiraiDoc = thiraiResponse.document
            val embedUrl = extractEmbedUrl(thiraiDoc) ?: return false
            
            // Step 3: Get the embed page with thiraisorgam referer
            val embedResponse = app.get(
                embedUrl,
                referer = thiraiResponse.url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
                )
            )
            
            val embedDoc = embedResponse.document
            
            // Step 4: Extract video sources from embed page
            extractVideoSources(embedDoc, embedResponse.url, callback)
            return true
            
        } catch (e: Exception) {
            // Silent fail - let other extractors try
            return false
        }
    }
    
    private fun extractEmbedUrl(doc: Document): String? {
        // Look for thrfive.io embed URLs
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
                return "https://thrfive.io/embed/$videoId?autoplay=false"
            }
        }
        
        return null
    }
    
    private suspend fun extractVideoSources(
        doc: Document,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val scripts = doc.select("script")
        
        // Method 1: JW Player setup extraction
        for (script in scripts) {
            val content = script.html()
            
            // Look for JW Player setup calls
            val jwSetupMatch = Regex("jwplayer\\([^)]*\\)\\.setup\\(([^;]+)\\)").find(content)
            if (jwSetupMatch != null) {
                val setupContent = jwSetupMatch.groupValues[1]
                extractFromJwPlayerSetup(setupContent, referer, callback)
                return
            }
            
            // Look for JW Player configuration objects
            val jwConfigMatch = Regex("var\\s+jwConfig\\s*=\\s*([^;]+);").find(content)
            if (jwConfigMatch != null) {
                val configContent = jwConfigMatch.groupValues[1]
                extractFromJwPlayerConfig(configContent, referer, callback)
                return
            }
            
            // Look for sources array in JW Player format
            val sourcesMatch = Regex("sources\\s*:\\s*\\[([^\\]]+)\\]").find(content)
            if (sourcesMatch != null) {
                val sourcesContent = sourcesMatch.groupValues[1]
                extractFromSourcesArray(sourcesContent, referer, callback)
                return
            }
        }
        
        // Method 2: Look for dynamically loaded playlist URLs
        for (script in scripts) {
            val content = script.html()
            
            // Common JW Player playlist patterns
            val playlistPatterns = listOf(
                Regex("file\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']"),
                Regex("playlist\\s*:\\s*[\"']([^\"']+)[\"']"),
                Regex("[\"']file[\"']\\s*:\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']"),
                Regex("sources\\s*:\\s*\\[\\{[^}]*file[\"']?\\s*:\\s*[\"']([^\"']+)[\"']")
            )
            
            for (pattern in playlistPatterns) {
                val match = pattern.find(content)
                if (match != null) {
                    val url = match.groupValues[1]
                    if (url.contains(".m3u8") || url.contains("playlist")) {
                        parseM3u8Playlist(url, referer, callback)
                        return
                    }
                }
            }
        }
        
        // Method 3: Look for Ajax/fetch calls that load playlist data
        for (script in scripts) {
            val content = script.html()
            
            // Look for API endpoints that might return video data
            val apiPatterns = listOf(
                Regex("ajax\\([^}]*url\\s*:\\s*[\"']([^\"']+)[\"']"),
                Regex("fetch\\([\"']([^\"']+)[\"']"),
                Regex("\\.get\\([\"']([^\"']+)[\"']"),
                Regex("xhr\\.open\\([\"']GET[\"'],\\s*[\"']([^\"']+)[\"']")
            )
            
            for (pattern in apiPatterns) {
                val match = pattern.find(content)
                if (match != null) {
                    val apiUrl = match.groupValues[1]
                    if (apiUrl.contains("playlist") || apiUrl.contains("source") || 
                        apiUrl.contains("stream") || apiUrl.contains("video")) {
                        try {
                            val apiResponse = app.get(
                                if (apiUrl.startsWith("http")) apiUrl else "https://thrfive.io$apiUrl",
                                referer = referer,
                                headers = mapOf(
                                    "Accept" to "application/json, text/plain, */*",
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Origin" to "https://thrfive.io"
                                )
                            )
                            
                            val responseText = apiResponse.text
                            if (responseText.contains(".m3u8")) {
                                // Try to extract URL from JSON response
                                val urlMatch = Regex("[\"']([^\"']*\\.m3u8[^\"']*)[\"']").find(responseText)
                                if (urlMatch != null) {
                                    parseM3u8Playlist(urlMatch.groupValues[1], referer, callback)
                                    return
                                }
                            }
                        } catch (e: Exception) {
                            // Continue with other methods
                        }
                    }
                }
            }
        }
        
        // Method 4: Look for base64 encoded or obfuscated URLs
        for (script in scripts) {
            val content = script.html()
            
            val encodedMatch = Regex("atob\\([\"']([^\"']+)[\"']\\)").find(content)
            if (encodedMatch != null) {
                try {
                    val decodedUrl = String(
                        android.util.Base64.decode(encodedMatch.groupValues[1], android.util.Base64.DEFAULT)
                    )
                    if (decodedUrl.contains(".m3u8")) {
                        parseM3u8Playlist(decodedUrl, referer, callback)
                        return
                    }
                } catch (e: Exception) {
                    // Continue with other methods
                }
            }
        }
        
        // Method 5: Look for direct m3u8 URLs in any script
        for (script in scripts) {
            val content = script.html()
            val m3u8Match = Regex("https://[^\"'\\s]+\\.m3u8[^\"'\\s]*").find(content)
            if (m3u8Match != null) {
                val masterUrl = m3u8Match.value
                parseM3u8Playlist(masterUrl, referer, callback)
                return
            }
        }
        
        // Method 6: Look for video elements (fallback)
        val videoElements = doc.select("video source[src]")
        for (videoElement in videoElements) {
            val src = videoElement.attr("src")
            if (src.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        src,
                        referer,
                        Qualities.Unknown.value,
                        isM3u8 = src.contains(".m3u8")
                    )
                )
            }
        }
    }
    
    private fun extractFromJwPlayerSetup(setupContent: String, referer: String, callback: (ExtractorLink) -> Unit) {
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
                    newExtractorLink(
                        name,
                        name,
                        fileUrl,
                        referer,
                        Qualities.Unknown.value,
                        isM3u8 = fileUrl.contains(".m3u8")
                    )
                )
            }
            
            // Look for playlist URL
            val playlistMatch = Regex("playlist\\s*:\\s*[\"']([^\"']+)[\"']").find(setupContent)
            if (playlistMatch != null) {
                val playlistUrl = playlistMatch.groupValues[1]
                // Fetch the playlist if it's an API endpoint
                if (playlistUrl.startsWith("http") || playlistUrl.startsWith("/")) {
                    fetchPlaylistData(playlistUrl, referer, callback)
                }
            }
        } catch (e: Exception) {
            // Continue
        }
    }
    
    private fun extractFromJwPlayerConfig(configContent: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val sourcesMatch = Regex("sources\\s*:\\s*\\[([^\\]]+)\\]").find(configContent)
            if (sourcesMatch != null) {
                extractFromSourcesArray(sourcesMatch.groupValues[1], referer, callback)
            }
        } catch (e: Exception) {
            // Continue
        }
    }
    
    private fun extractFromSourcesArray(sourcesContent: String, referer: String, callback: (ExtractorLink) -> Unit) {
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
                            newExtractorLink(
                                name,
                                name,
                                fileUrl,
                                referer,
                                Qualities.Unknown.value,
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
                    "Origin" to "https://thrfive.io"
                )
            )
            
            val responseText = response.text
            
            // Try to parse as JSON and extract video URLs
            val urlMatches = Regex("[\"']([^\"']*(?:\\.m3u8|\\.mp4)[^\"']*)[\"']").findAll(responseText)
            for (match in urlMatches) {
                val videoUrl = match.groupValues[1]
                if (videoUrl.isNotEmpty() && (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4"))) {
                    if (videoUrl.contains(".m3u8")) {
                        parseM3u8Playlist(videoUrl, referer, callback)
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                name,
                                videoUrl,
                                referer,
                                Qualities.Unknown.value,
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
                    "Origin" to "https://thrfive.io",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site"
                )
            )
            
            val playlistContent = playlistResponse.text
            
            // Parse master playlist for different quality variants
            val lines = playlistContent.split("\n")
            var currentQuality = Qualities.Unknown.value
            
            for (i in lines.indices) {
                val line = lines[i].trim()
                
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    // Extract resolution/quality info
                    val resolutionMatch = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                    if (resolutionMatch != null) {
                        val height = resolutionMatch.groupValues[2].toInt()
                        currentQuality = when {
                            height >= 1080 -> Qualities.P1080.value
                            height >= 720 -> Qualities.P720.value
                            height >= 480 -> Qualities.P480.value
                            height >= 360 -> Qualities.P360.value
                            else -> Qualities.Unknown.value
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
                    
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            variantUrl,
                            referer,
                            currentQuality,
                            isM3u8 = true
                        )
                    )
                }
            }
            
            // If no variants found, use master playlist directly
            if (!playlistContent.contains("#EXT-X-STREAM-INF:")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        masterUrl,
                        referer,
                        Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
            
        } catch (e: Exception) {
            // Fallback: return the URL as-is
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    masterUrl,
                    referer,
                    Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }
    }
}
