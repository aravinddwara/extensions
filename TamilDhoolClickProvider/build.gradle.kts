// Use an integer for version numbers
version = 1

cloudstream {
    description = "Watch Tamil Serials from Sun TV, Vijay TV, Zee Tamil and Live TV"
    authors = listOf("das")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("TvSeries", "Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=tamildhool.click&sz=%size%"

    isCrossPlatform = true
}
