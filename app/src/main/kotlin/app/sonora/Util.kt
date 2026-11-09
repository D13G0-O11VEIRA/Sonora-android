package app.sonora

import java.text.Normalizer

// ------------------------------------------------------------ nomes de arquivo
private val RESERVED = setOf(
    "CON", "PRN", "AUX", "NUL",
    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
)

private val FS_REPLACE = listOf(
    ":" to " -", "/" to "-", "\\" to "-", "|" to "-", "\"" to "'",
    "?" to "", "*" to "", "<" to "", ">" to "",
)

fun sanitizeFilename(name: String, maxLen: Int = 150): String {
    var n = name
    for ((bad, good) in FS_REPLACE) n = n.replace(bad, good)
    n = n.replace(Regex("[\\x00-\\x1f]"), "")
    n = n.replace(Regex("\\s+"), " ").trim().trim('.')
    if (n.uppercase() in RESERVED) n = "_$n"
    if (n.length > maxLen) n = n.substring(0, maxLen)
    return n.trimEnd(' ', '.')
}

/** Preenche '{artist}/{album}/{track} - {title}'. Devolve as partes do caminho (sem extensão). */
fun renderTemplate(template: String, values: Map<String, String>): List<String> {
    val filled = Regex("\\{(\\w+)\\}").replace(template.replace("\\", "/")) { m ->
        sanitizeFilename(values[m.groupValues[1]] ?: "")
    }
    val parts = mutableListOf<String>()
    for (raw in filled.split("/")) {
        var p = raw.replace(Regex("\\(\\s*\\)|\\[\\s*\\]"), "")
        p = p.replace(Regex("^[\\s\\-–_.]+|[\\s\\-–_.]+$"), "")
        p = p.replace(Regex("\\s+-\\s+-\\s+"), " - ")
        p = sanitizeFilename(p)
        if (p.isNotEmpty()) parts.add(p)
    }
    return if (parts.isEmpty()) listOf("faixa") else parts
}

// ------------------------------------------------------------ texto / título
private const val NOISE_WORDS =
    "official|oficial|lyrics?|letra|v[ií]deo|[aá]udio|audio|visuali[sz]er|hd|hq|4k|1080p|" +
        "clip|m/?v|remaster(?:ed)?(?:\\s*\\d{4})?|legendado|tradu[cç][aã]o|videoclipe?|full\\s*album|" +
        "with\\s+lyrics|lyric\\s*video|explicit"

private val BRACKET_NOISE = Regex(
    "\\s*[\\(\\[\\{][^\\)\\]\\}]*\\b(?:$NOISE_WORDS)\\b[^\\)\\]\\}]*[\\)\\]\\}]",
    RegexOption.IGNORE_CASE,
)
private val TRAIL_NOISE = Regex(
    "\\s*[-–|]\\s*(?:official\\s*)?(?:music\\s*)?(?:lyric\\s*)?(?:video|audio|visuali[sz]er)\\s*$",
    RegexOption.IGNORE_CASE,
)
private val FEAT_BRACKET = Regex("[\\(\\[]\\s*(?:feat(?:uring)?\\.?|ft\\.?)\\s+([^\\)\\]]+)[\\)\\]]", RegexOption.IGNORE_CASE)
private val FEAT_TAIL = Regex("\\s+(?:feat(?:uring)?\\.?|ft\\.?)\\s+(.+)$", RegexOption.IGNORE_CASE)

fun cleanTitle(title: String): String {
    var t = BRACKET_NOISE.replace(title, "")
    t = TRAIL_NOISE.replace(t, "")
    return t.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '|')
}

fun splitNames(s: String, splitAmpersand: Boolean = true): List<String> {
    var pattern = ",|;|\\s+x\\s+|\\s+and\\s+"
    if (splitAmpersand) pattern += "|\\s+&\\s+"
    return s.split(Regex(pattern, RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
}

/** 'Song (feat. A & B)' -> ('Song', ['A', 'B']) */
fun extractFeatured(text: String): Pair<String, List<String>> {
    val feats = mutableListOf<String>()
    var t = FEAT_BRACKET.replace(text) { m -> feats.addAll(splitNames(m.groupValues[1])); "" }
    t = FEAT_TAIL.replace(t) { m -> feats.addAll(splitNames(m.groupValues[1])); "" }
    return t.replace(Regex("\\s+"), " ").trim() to feats
}

fun cleanChannel(name: String): String {
    var n = name.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
    n = n.replace(Regex("\\s*(vevo|official|oficial)\\s*$", RegexOption.IGNORE_CASE), "")
    return n.trim()
}

fun norm(s: String): String {
    val d = Normalizer.normalize(s, Normalizer.Form.NFKD)
    val sb = StringBuilder()
    for (c in d) {
        if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(c)
    }
    return sb.toString().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

fun dedupe(items: List<String>): List<String> {
    val seen = HashSet<String>()
    val out = ArrayList<String>()
    for (it in items) {
        val k = norm(it)
        if (it.isNotEmpty() && seen.add(k)) out.add(it)
    }
    return out
}

class GuessedTrack(val artist: String, val title: String, val featured: List<String>)

/** Heurística para títulos do YouTube: devolve artista, título e participações. */
fun guessArtistTitle(rawTitle: String, channel: String = ""): GuessedTrack {
    val t = cleanTitle(rawTitle)
    var artist: String
    var song = t
    val parts = t.split(Regex("\\s+[-–—|]\\s+"), limit = 2)
    if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
        artist = parts[0].trim()
        song = parts[1].trim()
    } else {
        artist = cleanChannel(channel)
    }
    val (songClean, featsSong) = extractFeatured(song)
    val (artistClean, featsArtist0) = extractFeatured(artist)
    var featsArtist = featsArtist0
    artist = artistClean
    val main = splitNames(artist, splitAmpersand = false)
    if (main.size > 1) { // "A x B - Música": A principal, B participante
        artist = main[0]
        featsArtist = main.drop(1) + featsArtist
    }
    return GuessedTrack(artist, songClean, dedupe(featsArtist + featsSong))
}

// ------------------------------------------------------------ similaridade
private fun lcsMatches(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    var best = 0
    var endA = 0
    var endB = 0
    var prev = IntArray(b.length + 1)
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        for (j in 1..b.length) {
            if (a[i - 1] == b[j - 1]) {
                cur[j] = prev[j - 1] + 1
                if (cur[j] > best) {
                    best = cur[j]
                    endA = i
                    endB = j
                }
            }
        }
        prev = cur
    }
    if (best == 0) return 0
    return best +
        lcsMatches(a.substring(0, endA - best), b.substring(0, endB - best)) +
        lcsMatches(a.substring(endA), b.substring(endB))
}

fun similarity(a: String, b: String): Double {
    val na = norm(a)
    val nb = norm(b)
    if (na.isEmpty() || nb.isEmpty()) return 0.0
    var r = 2.0 * lcsMatches(na, nb) / (na.length + nb.length)
    val sh = if (na.length <= nb.length) na else nb
    val lg = if (na.length <= nb.length) nb else na
    if (sh.length >= 6 && lg.contains(sh)) r = maxOf(r, 0.8)
    return r
}

fun fmtSpeed(bytesPerSec: Double): String {
    var v = bytesPerSec
    val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
    for (u in units) {
        if (v < 1024) return "%.1f %s".format(v, u)
        v /= 1024
    }
    return "%.1f TB/s".format(v)
}
