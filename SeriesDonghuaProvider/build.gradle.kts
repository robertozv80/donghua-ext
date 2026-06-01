version = 1

cloudstream {
    language = "es"
    description = "SeriesDonghua - Donghuas y anime chino en español"
    authors = listOf("robertozv80")
    status = 1

    tvTypes = listOf("Anime")

    iconUrl = "https://www.google.com/s2/favicons?domain=seriesdonghua.com&sz=%size%"
}

dependencies {
    add("implementation", "com.github.recloudstream.cloudstream:library:-SNAPSHOT")
    add("implementation", "com.github.Blatzar:NiceHttp:0.4.11")
    add("implementation", "org.jsoup:jsoup:1.18.3")
}
