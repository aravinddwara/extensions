// use an integer for version numbers
version = 3

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    description = "Watch Tamil serials and shows from TamilDhool organized by series with episodes listed by date"
    authors = listOf("YourName")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=tamildhool.net&sz=%size%"

    isCrossPlatform = true
}
