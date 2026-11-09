package app.sonora

import java.io.ByteArrayOutputStream
import java.io.File

class TagFields(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val year: String,
    val track: String,   // "3" ou "3/12"
    val disc: String,    // "1" ou "1/2"
    val genre: String,
    val isrc: String,
    val comment: String,
    val lyrics: String,
    val sourceUrl: String,
)

/**
 * Gravador mínimo de ID3v2.3 (UTF-16), o formato que o Windows e a maioria dos players Android leem melhor.
 * Substitui qualquer ID3v2/ID3v1 existente (o ffmpeg do yt-dlp escreve uma tag própria) e preserva o áudio.
 */
object Tagger {
    private fun u16(s: String): ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)

    private fun int32(n: Int): ByteArray =
        byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    private fun synchsafe(n: Int): ByteArray =
        byteArrayOf(((n shr 21) and 0x7f).toByte(), ((n shr 14) and 0x7f).toByte(), ((n shr 7) and 0x7f).toByte(), (n and 0x7f).toByte())

    private fun frame(id: String, data: ByteArray): ByteArray =
        id.toByteArray(Charsets.US_ASCII) + int32(data.size) + byteArrayOf(0, 0) + data

    private fun text(id: String, s: String): ByteArray? =
        if (s.isBlank()) null else frame(id, byteArrayOf(1) + u16(s.trim()))

    /** COMM e USLT: codificação, idioma, descrição vazia, texto. */
    private fun commLike(id: String, s: String): ByteArray? =
        if (s.isBlank()) null
        else frame(id, byteArrayOf(1) + "eng".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) + u16(s.trim()))

    private fun apic(img: ByteArray): ByteArray {
        val mime = if (img.size > 8 && img[0] == 0x89.toByte() && img[1] == 0x50.toByte()) "image/png" else "image/jpeg"
        val data = byteArrayOf(0) + mime.toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 3, 0) + img
        return frame("APIC", data)
    }

    private fun url(id: String, s: String): ByteArray? =
        if (s.isBlank()) null else frame(id, s.trim().toByteArray(Charsets.ISO_8859_1))

    /** Remove ID3v2 do início e ID3v1 do fim do arquivo. */
    private fun stripId3(b: ByteArray): ByteArray {
        var start = 0
        var end = b.size
        if (b.size > 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) {
            val size = ((b[6].toInt() and 0x7f) shl 21) or ((b[7].toInt() and 0x7f) shl 14) or
                ((b[8].toInt() and 0x7f) shl 7) or (b[9].toInt() and 0x7f)
            val footer = if ((b[5].toInt() and 0x10) != 0) 10 else 0
            start = minOf(b.size, 10 + size + footer)
        }
        if (end - start >= 128 && b[end - 128] == 'T'.code.toByte() && b[end - 127] == 'A'.code.toByte() && b[end - 126] == 'G'.code.toByte()) {
            end -= 128
        }
        return b.copyOfRange(start, end)
    }

    fun write(file: File, f: TagFields, cover: ByteArray?) {
        val audio = stripId3(file.readBytes())
        val body = ByteArrayOutputStream()
        val frames = listOf(
            text("TIT2", f.title),
            text("TPE1", f.artist),
            text("TPE2", f.albumArtist),
            text("TALB", f.album),
            text("TYER", f.year.take(4)),
            text("TRCK", f.track),
            text("TPOS", f.disc),
            text("TCON", f.genre),
            text("TSRC", f.isrc),
            commLike("COMM", f.comment),
            commLike("USLT", f.lyrics),
            url("WOAS", f.sourceUrl),
            if (cover != null && cover.isNotEmpty()) apic(cover) else null,
        )
        for (fr in frames) if (fr != null) body.write(fr)
        val tag = body.toByteArray()

        val out = ByteArrayOutputStream(tag.size + audio.size + 10)
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        out.write(synchsafe(tag.size))
        out.write(tag)
        out.write(audio)
        file.writeBytes(out.toByteArray())
    }
}
