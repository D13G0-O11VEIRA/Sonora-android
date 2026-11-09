package app.sonora

class Candidate(
    val id: String,
    val url: String,
    val title: String,
    val channel: String,
    val duration: Double?,
)

// Palavras que indicam outra versão da música (penalizadas, a menos que estejam no título original).
private val BAD_WORDS = listOf(
    "live", "ao vivo", "cover", "karaoke", "reaction", "slowed", "sped up", "speed up", "nightcore",
    "8d", "remix", "instrumental", "acoustic", "tutorial", "lesson", "mashup", "reverb",
    "bass boosted", "1 hour", "loop",
)

fun scoreCandidate(meta: TrackMeta, c: Candidate): Double {
    val want = "${meta.mainArtist} ${meta.title}".trim()
    val nt = norm(c.title)
    val nchan = norm(c.channel)
    val nMetaTitle = norm(meta.title)

    var score = maxOf(similarity(c.title, want), similarity(c.title, meta.title)) * 35
    if (nMetaTitle.isNotEmpty() && nt.contains(nMetaTitle)) score += 10
    if (meta.mainArtist.isNotEmpty() && (nt.contains(norm(meta.mainArtist)) || nchan.contains(norm(meta.mainArtist)))) {
        score += 10
    }

    val dur = c.duration
    if (meta.duration > 0 && dur != null && dur > 0) {
        val diff = kotlin.math.abs(meta.duration - dur)
        score += when {
            diff <= 2 -> 40
            diff <= 5 -> 30
            diff <= 10 -> 15
            diff > 20 -> -40
            else -> 0
        }
    }

    val lowChan = c.channel.lowercase()
    val lowTitle = c.title.lowercase()
    if (lowChan.endsWith("- topic")) score += 25 // faixa "auto-gerada" = áudio de estúdio
    if ("official audio" in lowTitle || "audio oficial" in lowTitle) score += 12
    if ("official" in lowChan || "vevo" in lowChan) score += 6
    if ("lyric" in lowTitle) score += 2

    for (w in BAD_WORDS) {
        if (" $nt ".contains(" $w ") && !" $nMetaTitle ".contains(" $w ")) score -= 30
    }
    return score
}

/** Retorna o melhor candidato e sua pontuação (null se nenhum for confiável). */
fun pickBest(meta: TrackMeta, candidates: List<Candidate>): Pair<Candidate?, Double> {
    var best: Candidate? = null
    var bestScore = Double.NEGATIVE_INFINITY
    for (c in candidates) {
        val s = scoreCandidate(meta, c)
        if (s > bestScore) {
            best = c
            bestScore = s
        }
    }
    if (best == null || bestScore < 12) return null to bestScore
    return best to bestScore
}
