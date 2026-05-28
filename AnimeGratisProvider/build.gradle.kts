version = 1

cloudstream {
    language = "es"
    description = "AnimeGratis - Anime y donghua en español gratis"
    authors = listOf("robertozv80")
    status = 1

    tvTypes = listOf("Anime")

    iconUrl = "https://www.google.com/s2/favicons?domain=animegratis.net&sz=%size%"
}

dependencies {
    add("implementation", "org.jsoup:jsoup:1.18.3")
}
