version = 5

cloudstream {
    language = "es"
    description = "AnimeCube - Donghua 4K con subtitulos via Dailymotion"
    authors = listOf("robertozv80")
    status = 1

    tvTypes = listOf("Anime")

    iconUrl = "https://www.google.com/s2/favicons?domain=animecube.live&sz=%size%"
}

dependencies {
    add("cloudstream", "com.lagradost:cloudstream3:pre-release")
    add("implementation", "com.github.Blatzar:NiceHttp:0.4.11")
    add("implementation", "org.jsoup:jsoup:1.18.3")
}
