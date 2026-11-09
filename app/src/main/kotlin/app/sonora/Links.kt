package app.sonora

import java.net.URI
import java.net.URLDecoder

/** kind: spotify_track|spotify_album|spotify_playlist|spotify_artist|youtube_video|youtube_playlist|search|unsupported */
data class Link(val kind: String, val id: String, val url: String)

private val SPOTIFY = Regex(
    "(?:open\\.spotify\\.com/(?:intl-[a-z]{2}(?:-[a-z]{2})?/)?|spotify:)(track|album|playlist|artist)[/:]([A-Za-z0-9]{22})",
)
private val URL_RE = Regex("(https?://\\S+|spotify:(?:track|album|playlist|artist):[A-Za-z0-9]{22})")

/** Separa o texto colado em itens: URLs (várias por linha) ou texto de busca. */
fun parseInputs(text: String): List<String> {
    val items = mutableListOf<String>()
    for (line0 in text.lines()) {
        val line = line0.trim()
        if (line.isEmpty()) continue
        val urls = URL_RE.findAll(line).map { it.value }.toList()
        if (urls.isEmpty()) items.add(line) else items.addAll(urls)
    }
    return items
}

private fun queryParams(query: String?): Map<String, String> {
    if (query.isNullOrEmpty()) return emptyMap()
    val out = HashMap<String, String>()
    for (pair in query.split("&")) {
        val i = pair.indexOf('=')
        if (i <= 0) continue
        val k = URLDecoder.decode(pair.substring(0, i), "UTF-8")
        val v = URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        if (k !in out) out[k] = v
    }
    return out
}

fun classify(rawInput: String, playlistIfBoth: Boolean = false): Link {
    val raw = rawInput.trim().trim('<', '>', '"', '\'')
    SPOTIFY.find(raw)?.let { return Link("spotify_${it.groupValues[1]}", it.groupValues[2], raw) }

    if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
        return Link("search", raw, raw) // texto livre => busca no YouTube
    }

    val uri = try {
        URI(raw)
    } catch (e: Exception) {
        return Link("unsupported", "", raw)
    }
    val host = (uri.host ?: "").lowercase().removePrefix("www.")
    val q = queryParams(uri.rawQuery)
    var vid = q["v"]
    val lst = q["list"]

    fun videoOrPlaylist(videoId: String?, listId: String?): Link? {
        val realList = !listId.isNullOrEmpty() && !listId.startsWith("RD") // RD = "mix" automático
        if (!videoId.isNullOrEmpty() && !(realList && playlistIfBoth)) {
            return Link("youtube_video", videoId, "https://www.youtube.com/watch?v=$videoId")
        }
        if (realList && listId != null) {
            return Link("youtube_playlist", listId, "https://www.youtube.com/playlist?list=$listId")
        }
        return null
    }

    if (host == "youtu.be") {
        val first = (uri.path ?: "").trim('/').split("/").firstOrNull().orEmpty()
        videoOrPlaylist(first.ifEmpty { null }, lst)?.let { return it }
    } else if (host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")) {
        val parts = (uri.path ?: "").split("/").filter { it.isNotEmpty() }
        if (parts.size > 1 && parts[0] in listOf("shorts", "embed", "live", "v")) vid = parts[1]
        if (parts.isNotEmpty() && parts[0] == "playlist" && !lst.isNullOrEmpty()) {
            return Link("youtube_playlist", lst, "https://www.youtube.com/playlist?list=$lst")
        }
        videoOrPlaylist(vid, lst)?.let { return it }
    }
    return Link("unsupported", "", raw)
}
