version = 1

cloudstream {
    language = "es"
    description = "LmAnime - Donghua y anime chino con servidores en español e inglés"
    authors = listOf("robertozv80")
    status = 1

    tvTypes = listOf("Anime")

    iconUrl = "https://www.google.com/s2/favicons?domain=lmanime.com&sz=%size%"
}

dependencies {
    add("cloudstream", "com.lagradost:cloudstream3:pre-release")
    add("implementation", "com.github.Blatzar:NiceHttp:0.4.11")
    add("implementation", "org.jsoup:jsoup:1.18.3")
}
