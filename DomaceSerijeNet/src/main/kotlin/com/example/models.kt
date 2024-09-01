package com.example.models

data class Server(
    val id: String,
    val name: String,
    val url: String,
    val streamUrl: String
) {
    override fun toString(): String {
        return "ID: $id\nName: $name\nURL: $url\nStream URL: $streamUrl\n"
    }
}

data class Episode(
    val img: String?,
    val seriesTitle: String?,
    val episodeTitle: String?,
    val link: String?,
    val fullLink: String?,
    val season: Int?,
    val episode: Any?
) {
    override fun toString(): String {
        return "Serija: $seriesTitle\nSezona: $season\nEpizoda: $episode\nLink: $link\nFull link: $fullLink\nSlika: $img\n"
    }
}