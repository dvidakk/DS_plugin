package com.example

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchQuality
import com.example.models.Episode
import com.example.models.Server
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import androidx.core.text.isDigitsOnly
import com.lagradost.cloudstream3.MainPageRequest

class DomaceSerijeNetProvider(val plugin: DomaceSerijeNetPlugin) : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://domaceserije.net"
    override var name = "DomaceSerijeNet"
    override val supportedTypes = setOf(TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
    )

    private val SEASON_EPISODE_PATTERN = Pattern.compile("Sezona (\\d+) (?:Epizoda (\\d+)|Epizod[ae] Sve)")
    private val CHANGE_FUNC_PATTERN = Pattern.compile("function change\\(id\\)\\s*\\{.*?switch\\s*\\(id\\)\\s*\\{(.*?)default:", Pattern.DOTALL)
    private val URL_PATTERN = Pattern.compile("case\\s*(\\d+):\\s*src\\s*=\\s*\"(.*?)\";")
    private val VID_SRC_PATTERN = "\\.\\./zsrv/sro1\\?search"

    // enable this when your provider has a main page
    override val hasMainPage = true

    // this function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?search=$query"
        val page = getPage(searchUrl, headers)
        val document = Jsoup.parse(page)

        // first we select all the elements that contain the search results "#latestalbum > center:nth-child(6) > div:nth-child(1) > div:nth-child(1)"

        val cerasol = document.select("#latestalbum > center:nth-child(6) > div:nth-child(1) > div:nth-child(1)")

        val searchResults = cerasol.select("div.img__wrap").map { element ->
            val title = element.select("p.img__description").text().trim()
            val img = element.select("img.img__img").attr("src")
            val link = element.select("a").attr("href")
            val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"

            object : SearchResponse {
                override var id: Int? = null
                override var name: String = title
                override var url: String = fullLink
                override var apiName: String = this@DomaceSerijeNetProvider.name
                override var type: TvType? = TvType.Movie
                override var posterUrl: String? = img
                override var posterHeaders: Map<String, String>? = null
                override var quality: SearchQuality? = null
            }
        }
        return searchResults
    }

    private fun parseSeasonEpisode(title: String): Pair<Int?, Any?> {
        val match = SEASON_EPISODE_PATTERN.matcher(title)
        return if (match.find()) {
            val season = match.group(1)?.toInt()
            val episode = match.group(2)
            if (episode != null && episode.isDigitsOnly()) {
                season to episode.toInt()
            } else {
                season to "all"
            }
        } else {
            null to null
        }
    }

    private fun getPage(url: String, headers: Map<String, String>? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        headers?.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        connection.inputStream.bufferedReader().use { return it.readText() }
    }

    private fun parseEpisode(item: org.jsoup.nodes.Element): Episode {
        val img = item.selectFirst("img")?.attr("src")
        val seriesTitle = item.selectFirst("strong")?.text()
        val episodeTitle = item.selectFirst("i")?.text()
        val link = item.selectFirst("a")?.attr("href")
        val fullLink = link?.let { "$mainUrl${it.removePrefix("/..")}" }
        val (season, episode) = parseSeasonEpisode(episodeTitle ?: "")
        return Episode(img, seriesTitle, episodeTitle, link, fullLink, season, episode)
    }

    private fun findIframeWithSrcPattern(html: String, pattern: String): List<String> {
        val doc = Jsoup.parse(html)
        val iframes = doc.select("iframe[src~=$pattern]")
        return iframes.mapNotNull { it.attr("src") }
    }

    private fun extractServersWithUrls(scriptContent: String, html: String): List<Server> {
        val changeFuncMatch = CHANGE_FUNC_PATTERN.matcher(scriptContent)
        val urls = mutableMapOf<String, String>()
        if (changeFuncMatch.find()) {
            val switchContent = changeFuncMatch.group(1)
            val urlMatches = URL_PATTERN.matcher(switchContent)
            while (urlMatches.find()) {
                urls[urlMatches.group(1)] = urlMatches.group(2)
            }
        }

        val doc = Jsoup.parse(html)
        val servers = mutableListOf<Server>()
        val dropdown = doc.selectFirst("div.dropdown-content")
        dropdown?.select("a[name=link]")?.forEach { link ->
            val serverId = link.attr("id")
            val serverName = link.text().trim()
            val url = urls[serverId] ?: ""
            val streamUrl = if (url.isNotEmpty()) url.replace("..", mainUrl) else ""
            servers.add(Server(serverId, serverName, url, streamUrl))
        }

        return servers
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageContent = getPage(mainUrl, headers)
        val document = Jsoup.parse(pageContent)
    
        // Parse the latest episodesss
        val episodes = document.select(".eps .epizodenove").map { parseEpisode(it) }
        val homePageList = HomePageList("Poslednje Epizode", episodes.map { episode ->
            object : SearchResponse {
                override var id: Int? = null
                override var name: String = episode.seriesTitle ?: ""
                override var url: String = episode.fullLink ?: ""
                override var apiName: String = this@DomaceSerijeNetProvider.name
                override var type: TvType? = TvType.Movie
                override var posterUrl: String? = episode.img
                override var posterHeaders: Map<String, String>? = null
                override var quality: SearchQuality? = null
            }
        })
    
        // Parse the latest added series
        val lastAddedSeries = document.select("div.container:nth-child(4) > div.col-md-12.col-sm-6 > div.img__wrap")
        val latestAddedSeriesList = HomePageList("Poslednje Dodate - Serije", lastAddedSeries.map { element ->
            val title = element.select("p.img__description").text().trim()
            val img = element.select("img.img__img").attr("src")
            val link = element.select("a").attr("href")
            val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
    
            object : SearchResponse {
                override var id: Int? = null
                override var name: String = title
                override var url: String = fullLink
                override var apiName: String = this@DomaceSerijeNetProvider.name
                override var type: TvType? = TvType.Movie
                override var posterUrl: String? = img
                override var posterHeaders: Map<String, String>? = null
                override var quality: SearchQuality? = null
            }
        })
    
        // Parse other content
        val otherElements = document.select("div.container:nth-child(11) > div.col-md-12.col-sm-6 > div.img__wrap")
        val otherList = HomePageList("Ostalo", otherElements.map { element ->
            val title = element.select("p.img__description").text().trim()
            val img = element.select("img.img__img").attr("src")
            val link = element.select("a").attr("href")
            val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
        
            object : SearchResponse {
                override var id: Int? = null
                override var name: String = title
                override var url: String = fullLink
                override var apiName: String = this@DomaceSerijeNetProvider.name
                override var type: TvType? = TvType.Movie
                override var posterUrl: String? = img
                override var posterHeaders: Map<String, String>? = null
                override var quality: SearchQuality? = null
            }
        })
    
        return HomePageResponse(listOf(homePageList, latestAddedSeriesList, otherList))
    }
}