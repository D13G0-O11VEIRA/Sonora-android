package app.sonora

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lê METADADOS de faixa/álbum/playlist do Spotify pela página "embed" pública (sem login).
 * O áudio nunca vem do Spotify (DRM): ele é buscado no YouTube pela melhor correspondência.
 * Playlists sem login trazem até ~100 faixas. Álbum/ano/gênero faltantes são completados pelo iTunes.
 */
object SpotifyResolver {

    private fun walk(o: Any?, visit: (JSONObject) -> Boolean): Boolean {
        when (o) {
            is JSONObject -> {
                if (visit(o)) return true
                val keys = o.keys()
                while (keys.hasNext()) {
                    if (walk(o.opt(keys.next()), visit)) return true
                }
            }
            is JSONArray -> {
                for (i in 0 until o.length()) {
                    if (walk(o.opt(i), visit)) return true
                }
            }
        }
        return false
    }

    private fun findEntity(data: JSONObject): JSONObject? {
        var cur: JSONObject? = data
        for (k in listOf("props", "pageProps", "state", "data", "entity")) {
            cur = cur?.optJSONObject(k)
        }
        if (cur != null) return cur
        var found: JSONObject? = null
        walk(data) { d ->
            val looksRight = d.has("trackList") || (d.has("artists") && d.has("duration") && d.has("name"))
            if (looksRight) found = d
            looksRight
        }
        return found
    }

    private fun clean(s: String) = s.replace('\u00a0', ' ').trim()

    private fun splitArtists(s: String): List<String> =
        clean(s).split(Regex(",\\s*|\\s*;\\s*")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun bestCover(entity: JSONObject): String {
        var bestUrl = ""
        var bestWidth = -1
        for (key in listOf("coverArt", "visualIdentity", "images", "image")) {
            walk(entity.opt(key)) { d ->
                val url = d.s("url")
                if (url.startsWith("http")) {
                    val w = maxOf(d.i("width"), d.i("maxWidth"))
                    if (w > bestWidth) {
                        bestWidth = w
                        bestUrl = url
                    }
                }
                false
            }
        }
        return bestUrl
    }

    fun resolve(kind: String, id: String, source: String, log: (String) -> Unit): Resolved {
        val html = Net.getText(
            "https://open.spotify.com/embed/$kind/$id",
            mapOf("User-Agent" to Net.UA_BROWSER, "Accept-Language" to "en-US,en;q=0.9"),
        )
        val m = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: throw IllegalStateException("A página do Spotify mudou de formato. Use o link do YouTube.")
        val data = JSONObject(m.groupValues[1])
        val ent = findEntity(data) ?: throw IllegalStateException("Não consegui interpretar a página do Spotify.")

        val cover = bestCover(ent)
        val name = clean(ent.s("name").ifEmpty { ent.s("title") })

        if (kind == "track") {
            val artistsArr = ent.optJSONArray("artists")
            var artists = mutableListOf<String>()
            if (artistsArr != null) {
                for (i in 0 until artistsArr.length()) {
                    val n = artistsArr.optJSONObject(i)?.s("name").orEmpty()
                    if (n.isNotEmpty()) artists.add(n)
                }
            }
            if (artists.isEmpty()) artists = splitArtists(ent.s("subtitle")).toMutableList()
            val iso = ent.optJSONObject("releaseDate")?.s("isoString").orEmpty()
            val meta = TrackMeta(
                title = name,
                artists = artists,
                year = iso.take(4),
                duration = ent.d("duration") / 1000.0,
                coverUrl = cover,
                thumbUrl = cover,
                sourceUrl = source,
                spotifyId = id,
                origin = "spotify",
            )
            return Resolved(listOf(meta), "", "single")
        }

        val items = ent.optJSONArray("trackList") ?: JSONArray()
        if (items.length() == 0) throw IllegalStateException("Nenhuma faixa encontrada (playlist privada ou vazia?).")
        if (items.length() >= 100) log("Aviso: sem login o Spotify entrega no máximo ~100 faixas por playlist.")

        val albumArtist = if (kind == "album") clean(ent.s("subtitle")) else ""
        val tracks = mutableListOf<TrackMeta>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val title = clean(it.s("title"))
            if (title.isEmpty()) continue
            val uri = it.s("uri")
            val tid = if (uri.startsWith("spotify:track:")) uri.substringAfterLast(':') else ""
            val t = TrackMeta(
                title = title,
                artists = splitArtists(it.s("subtitle")),
                duration = it.d("duration") / 1000.0,
                spotifyId = tid,
                origin = "spotify",
                sourceUrl = if (tid.isNotEmpty()) "https://open.spotify.com/track/$tid" else source,
            )
            if (kind == "album") {
                t.album = name
                t.albumArtist = albumArtist
                t.trackNo = i + 1
                t.trackTotal = items.length()
                t.coverUrl = cover
                t.thumbUrl = cover
            }
            tracks.add(t)
        }
        return Resolved(tracks, name.ifEmpty { if (kind == "album") "Álbum" else "Playlist" }, kind)
    }
}
