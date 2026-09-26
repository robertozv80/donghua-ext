version = 2

cloudstream {
    language = "es"
    description = "TioDonghua - Donghuas, películas y episodios sub español (tiodonghua.lat)"
    authors = listOf("robertozv80")
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    iconUrl = "https://www.google.com/s2/favicons?domain=tiodonghua.lat&sz=%size%"
}

dependencies {
    add("cloudstream", "com.lagradost:cloudstream3:pre-release")
    add("implementation", "com.github.Blatzar:NiceHttp:0.4.11")
    add("implementation", "org.jsoup:jsoup:1.18.3")
}
