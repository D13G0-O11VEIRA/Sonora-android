package app.sonora

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Enriquecimento online (APIs públicas, sem chave): iTunes Search, LRCLIB e capas. */
object Enrich {
    private val rlLock = Any()
    private var nextSlot = 0L
    @Volatile private var itunesBlockedUntil = 0L

    /** A API do iTunes é sensível a rajadas: garante intervalo mínimo entre chamadas. */
    private fun itunesWait() {
        val delayMs: Long
        synchronized(rlLock) {
            val now = System.currentTimeMillis()
            delayMs = maxOf(0L, nextSlot - now)
            nextSlot = maxOf(now, nextSlot) + 2500L
        }
        if (delayMs > 0) Thread.sleep(delayMs)
    }

    private fun hiresArtwork(url: String) =
        url.replace(Regex("/\\d+x\\d+(?:bb)?\\.(jpg|png)$"), "/1000x1000bb.$1")

    /** Completa os campos vazios com o melhor resultado do iTunes. Retorna true se achou. */
    fun itunes(meta: TrackMeta, log: (String) -> Unit): Boolean {
        if (meta.title.isEmpty() || System.currentTimeMillis() < itunesBlockedUntil) return false
        val cleanTitle = extractFeatured(meta.title).first
        val term = "${meta.mainArtist} $cleanTitle".trim()

        for (country in listOf("US", "BR")) {
            val results: JSONArray
            try {
                itunesWait()
                val url = Net.url(
                    "https://itunes.apple.com/search",
                    mapOf("term" to term, "media" to "music", "entity" to "song", "limit" to "10", "country" to country),
                )
                results = JSONObject(Net.getText(url)).optJSONArray("results") ?: JSONArray()
            } catch (e: HttpException) {
                if (e.code == 403 || e.code == 429) {
                    itunesBlockedUntil = System.currentTimeMillis() + 90_000
                    log("iTunes limitou as consultas; pausando por 90 s.")
                }
                return false
            } catch (e: Exception) {
                log("iTunes indisponível: ${e.javaClass.simpleName}")
                return false
            }

            var best: JSONObject? = null
            var bestScore = 0.0
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val candTitle = extractFeatured(r.s("trackName")).first
                val tSim = similarity(candTitle, cleanTitle)
                val aSim = if (meta.artists.isNotEmpty()) meta.artists.maxOf { similarity(r.s("artistName"), it) } else 0.5
                var score = 0.6 * tSim + 0.4 * aSim
                val ms = r.d("trackTimeMillis")
                if (meta.duration > 0 && ms > 0) {
                    val diff = kotlin.math.abs(meta.duration - ms / 1000.0)
                    score += if (diff <= 4) 0.1 else if (diff > 15) -0.15 else 0.0
                }
                if (tSim >= 0.7 && score > bestScore) {
                    best = r
                    bestScore = score
                }
            }
            if (best != null && bestScore >= 0.78) {
                apply(meta, best)
                return true
            }
        }
        return false
    }

    private fun apply(m: TrackMeta, r: JSONObject) {
        if (m.artists.isEmpty() && r.s("artistName").isNotEmpty()) m.artists = listOf(r.s("artistName"))
        if (m.album.isEmpty()) m.album = r.s("collectionName").replace(Regex("\\s+-\\s+Single$"), "").trim()
        if (m.albumArtist.isEmpty()) m.albumArtist = r.s("collectionArtistName")
        if (m.year.isEmpty()) m.year = r.s("releaseDate").take(4)
        if (m.genre.isEmpty()) m.genre = r.s("primaryGenreName")
        if (m.trackNo == 0) m.trackNo = r.i("trackNumber")
        if (m.trackTotal == 0) m.trackTotal = r.i("trackCount")
        if (m.discNo == 0) m.discNo = r.i("discNumber")
        if (m.discTotal == 0) m.discTotal = r.i("discCount")
        if (m.coverUrl.isEmpty()) m.coverUrl = hiresArtwork(r.s("artworkUrl100"))
    }

    /** Letra (texto simples) do LRCLIB; "" se não achar. */
    fun lyrics(meta: TrackMeta, log: (String) -> Unit): String {
        if (meta.title.isEmpty() || meta.mainArtist.isEmpty()) return ""
        val cleanTitle = extractFeatured(meta.title).first
        val items: JSONArray
        try {
            val url = Net.url(
                "https://lrclib.net/api/search",
                mapOf("track_name" to cleanTitle, "artist_name" to meta.mainArtist),
            )
            items = JSONArray(Net.getText(url))
        } catch (e: Exception) {
            log("Letras indisponíveis: ${e.javaClass.simpleName}")
            return ""
        }
        var best: JSONObject? = null
        var bestScore = 0.0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            if (it.optBoolean("instrumental", false)) continue
            var score = similarity(it.s("trackName"), cleanTitle) + similarity(it.s("artistName"), meta.mainArtist)
            val dur = it.d("duration")
            if (meta.duration > 0 && dur > 0) {
                val diff = kotlin.math.abs(meta.duration - dur)
                if (diff > 8) continue
                score += if (diff <= 3) 0.4 else 0.1
            }
            if (it.s("syncedLyrics").isNotEmpty()) score += 0.1
            if (score > bestScore) {
                best = it
                bestScore = score
            }
        }
        if (best == null || bestScore < 1.5) return ""
        return best.s("plainLyrics").trim()
    }

    /** Baixa e converte para JPEG quadrado (corta o centro), no máximo 1000x1000. */
    fun cover(url: String): ByteArray? {
        val data = Net.getBytes(url)
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val side = minOf(bmp.width, bmp.height)
        var sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        if (side > 1000) sq = Bitmap.createScaledBitmap(sq, 1000, 1000, true)
        val out = ByteArrayOutputStream()
        sq.compress(Bitmap.CompressFormat.JPEG, 92, out)
        return out.toByteArray()
    }
}
