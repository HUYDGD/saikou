package ani.saikou.parsers.anime

import android.net.Uri
import ani.saikou.*
import ani.saikou.anilist.Anilist
import ani.saikou.parsers.*
import ani.saikou.parsers.anime.extractors.FPlayer
import ani.saikou.parsers.anime.extractors.GogoCDN
import ani.saikou.parsers.anime.extractors.StreamSB
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.DecimalFormat

class AllAnime : AnimeParser() {
    override val name = "AllAnime"
    override val saveName = "allanime"
    override val hostUrl = "https://allanime.to"
    override val isDubAvailableSeparately = true

    private val apiHost = "https://api.allanime.co"
    private val ytAnimeCoversHost = "https://wp.youtube-anime.com/aln.youtube-anime.com"
    private val idRegex = Regex("${hostUrl}/anime/(\\w+)")
    private val epNumRegex = Regex("/[sd]ub/(\\d+)")


    private val idHash = "259ae45c19ceff2f855215bb82d377fe7b0ab661f9abcd41538bda935e9cb299"
    private val episodeInfoHash = "73d998d209d6d8de325db91ed8f65716dce2a1c5f4df7d304d952fa3f223c9e8"
    private val searchHash = "c4305f3918591071dfecd081da12243725364f6b7dd92072df09d915e390b1b7"
    private val videoServerHash = "919e327075ac9e249d003aa3f804a48bbdf22d7b1d107ffe659accd54283ce48"

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?): List<Episode> {
        val showId = idRegex.find(animeLink)?.groupValues?.get(1)
        if (showId != null) {
            val episodeInfos = getEpisodeInfos(showId)
            val format = DecimalFormat("#####.#####")
            return episodeInfos?.sortedBy { it.episodeIdNum }?.map { epInfo ->
                val link = """${hostUrl}/anime/$showId/episodes/${if (selectDub) "dub" else "sub"}/${epInfo.episodeIdNum}"""
                val epNum = format.format(epInfo.episodeIdNum).toString()
                val thumbnail = epInfo.thumbnails?.let {

                    if (it.isNotEmpty()) {
                        var url = it[0]
                        if (!url.startsWith("https")) {
                            url = "$ytAnimeCoversHost$url"
                        }
                        FileUrl(url)
                    } else {
                        null
                    }
                }
                val title = epInfo.notes?.substringBefore("<note-split>")
                var desc = epInfo.notes?.substringAfter("<note-split>","")
                desc = if(desc?.isEmpty() == true) null else desc
                Episode(epNum, link = link, title, thumbnail, desc)
            } ?: emptyList()
        }
        return emptyList()
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Map<String, String>?): List<VideoServer> {
        val showId = idRegex.find(episodeLink)?.groupValues?.get(1)
        val videoServers = mutableListOf<VideoServer>()
        val episodeNum = epNumRegex.find(episodeLink)?.groupValues?.get(1)
        if (showId != null && episodeNum != null) {
            val variables =
                """{"showId":"$showId","translationType":"${if (selectDub) "dub" else "sub"}","episodeString":"$episodeNum"}"""
            graphqlQuery(
                variables,
                videoServerHash
            ).data?.episode?.sourceUrls?.forEach { source ->
                // It can be that two different actual sources share the same sourceName
                var serverName = source.sourceName
                var sourceNum = 2
                // Sometimes provides relative links just because ¯\_(ツ)_/¯
                while (videoServers.any { it.name == serverName }) {
                    serverName = "${source.sourceName} ($sourceNum)"
                    sourceNum++
                }

                if (source.sourceUrl.toHttpUrlOrNull() == null) {
                    val jsonUrl = """https://allanimenews.com/${source.sourceUrl.replace("clock", "clock.json").substring(1)}"""
                    videoServers.add(VideoServer(serverName, jsonUrl, source.type))
                } else {
                    videoServers.add(VideoServer(serverName, source.sourceUrl, source.type))
                }
            }

        }
        return videoServers
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor? {
        if (server.extraData as? String? == "player")
            return AllAnimeExtractor(server, true)
        val serverUrl = Uri.parse(server.embed.url)
        val domain = serverUrl.host ?: return null
        val path = serverUrl.path ?: return null
        val extractor: VideoExtractor? = when {
            "gogo" in domain    -> GogoCDN(server)
            "goload" in domain  -> GogoCDN(server)
            "sb" in domain      -> StreamSB(server)
            "sss" in domain     -> StreamSB(server)
            "fplayer" in domain -> FPlayer(server)
            "fembed" in domain  -> FPlayer(server)
            "apivtwo" in path   -> AllAnimeExtractor(server)
            else                -> null
        }
        return extractor
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val variables =
            """{"search":{"allowAdult":${Anilist.adult},"query":"$query"},"translationType":"${if (selectDub) "dub" else "sub"}"}"""
        return graphqlQuery(variables, searchHash).data?.shows?.edges?.map { show ->
            val link = """${hostUrl}/anime/${show.id}"""
            val otherNames = mutableListOf<String>()
            show.englishName?.let { otherNames.add(it) }
            show.nativeName?.let { otherNames.add(it) }
            show.altNames?.forEach { otherNames.add(it) }

            ShowResponse(
                show.name,
                link,
                show.thumbnail?: "https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/default.jpg",
                otherNames,
                show.availableEpisodes.let { if (selectDub) it.dub else it.sub }
            )
        } ?: emptyList()
    }

    private suspend fun graphqlQuery(variables: String, persistHash: String): Query {
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$persistHash"}}"""
        val res = client.get(
            "$apiHost/allanimeapi",
            params = mapOf(
                "variables" to variables,
                "extensions" to extensions
            )
        ).parsed<Query>()
        if(res.data==null) throw Exception(res.errors!![0].message)
        return res
    }

    private suspend fun getEpisodeInfos(showId: String): List<EpisodeInfo>? {
        val variables = """{"_id": "$showId"}"""
        val show = graphqlQuery(variables, idHash).data?.show
        if (show != null) {
            val epCount = if (selectDub) show.availableEpisodes.dub else show.availableEpisodes.sub
            val epVariables = """{"showId":"$showId","episodeNumStart":0,"episodeNumEnd":${epCount}}"""
            return graphqlQuery(
                epVariables,
                episodeInfoHash
            ).data?.episodeInfos
        }
        return null
    }

    private class AllAnimeExtractor(override val server: VideoServer, val direct: Boolean = false) : VideoExtractor() {
        override suspend fun extract(): VideoContainer {
            val url = server.embed.url
            return if (direct)
                VideoContainer(listOf(Video(null, VideoType.CONTAINER, url, getSize(url))))
            else {
                val res = client.get(url).parsed<VideoResponse>()
                val sub = mutableListOf<Subtitle>()
                val vid = res.links?.asyncMapNotNull { i ->
                    i.subtitles?.forEach {
                        if (it.label?.contains("vtt") == true)
                            sub.add(Subtitle(it.lang ?: return@forEach, it.src ?: return@forEach))
                    }
                    when {
                        i.crIframe == true -> {
                            i.portData?.streams?.mapNotNull {
                                when {
                                    it.format == "adaptive_dash" && it.hardsubLang == "en-US"
                                         ->
                                        Video(null, VideoType.DASH, it.url ?: return@mapNotNull null, null, "DASH")
                                    it.format == "adaptive_hls" && it.hardsubLang == "en-US"
                                         ->
                                        Video(null, VideoType.M3U8, it.url ?: return@mapNotNull null, null, "M3U8")
                                    else -> null
                                }
                            }
                        }
                        i.hls == true      -> listOf(
                            Video(null, VideoType.M3U8, i.link ?: return@asyncMapNotNull null, null, i.resolutionStr)
                        )
                        i.mp4 == true      -> listOf(
                            Video(
                                null, VideoType.CONTAINER, i.link ?: return@asyncMapNotNull null,
                                getSize(i.link), i.resolutionStr
                            )
                        )
                        else               -> null
                    }
                }?.flatten() ?: listOf()
                VideoContainer(vid,sub)
            }
        }
    }

    @Serializable
    private data class Query(
        @SerialName("data") var data: Data?,
        var errors : List<Error>?
    ) {

        @Serializable
        data class Error(
            var message: String
        )

        @Serializable
        data class Data(
            @SerialName("shows") val shows: ShowsConnection?,
            @SerialName("show") val show: Show?,
            @SerialName("episodeInfos") val episodeInfos: List<EpisodeInfo>?,
            @SerialName("episode") val episode: AllAnimeEpisode?,
        )

        @Serializable
        data class ShowsConnection(
            @SerialName("edges") val edges: List<Show>
        )

        @Serializable
        data class Show(
            @SerialName("_id") val id: String,
            @SerialName("name") val name: String,
            @SerialName("englishName") val englishName: String?,
            @SerialName("nativeName") val nativeName: String?,
            @SerialName("thumbnail") val thumbnail: String?,
            @SerialName("availableEpisodes") val availableEpisodes: AvailableEpisodes,
            @SerialName("altNames") val altNames: List<String>?
        )

        @Serializable
        data class AvailableEpisodes(
            @SerialName("sub") val sub: Int,
            @SerialName("dub") val dub: Int,
            // @SerialName("raw") val raw: Int,
        )

        @Serializable
        data class AllAnimeEpisode(
            @SerialName("sourceUrls") var sourceUrls: List<SourceUrl>
        )

        @Serializable
        data class SourceUrl(
            val sourceUrl: String,
            val sourceName: String,
            val type: String
        )
    }

    @Serializable
    private data class EpisodeInfo(
        // Episode "numbers" can have decimal values, hence float
        @SerialName("episodeIdNum") val episodeIdNum: Float,
        @SerialName("notes") val notes: String?,
        @SerialName("thumbnails") val thumbnails: List<String>?,
        @SerialName("vidInforssub") val vidInforssub: VidInfo?,
        @SerialName("vidInforsdub") val vidInforsdub: VidInfo?,
    ) {
        @Serializable
        data class VidInfo(
            // @SerialName("vidPath") val vidPath
            @SerialName("vidResolution") val vidResolution: Int?,
            @SerialName("vidSize") val vidSize: Double?,
        )
    }

    @Serializable
    private data class VideoResponse(
        val links: List<Link>? = null
    ) {
        @Serializable
        data class Link(
            val link: String? = null,
            val crIframe: Boolean? = null,
            val portData: PortData? = null,
            val resolutionStr: String? = null,
            val hls: Boolean? = null,
            val mp4: Boolean? = null,
            val subtitles: List<Subtitle>? = null
        )

        @Serializable
        data class PortData(
            val streams: List<Stream>? = null
        )

        @Serializable
        data class Stream(
            val format: String? = null,
            val url: String? = null,

            @SerialName("audio_lang")
            val audioLang: String? = null,

            @SerialName("hardsub_lang")
            val hardsubLang: String? = null
        )

        @Serializable
        data class Subtitle(
            val lang: String? = null,
            val src: String? = null,
            val label: String? = null,
            val default: String? = null
        )
    }
}


