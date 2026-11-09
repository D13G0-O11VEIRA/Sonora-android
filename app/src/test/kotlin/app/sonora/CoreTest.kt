package app.sonora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UtilTest {
    @Test fun sanitize() {
        assertEquals("AC-DC - 'Back' in Black", sanitizeFilename("AC/DC: \"Back\" in Black?"))
        assertEquals("_CON", sanitizeFilename("CON"))
        assertEquals("Nome", sanitizeFilename("  Nome...  "))
    }

    @Test fun template() {
        val v = mapOf("artist" to "Mother Mother", "title" to "Burning Pile", "album" to "O My Heart", "track" to "02")
        assertEquals(listOf("Mother Mother - Burning Pile"), renderTemplate("{artist} - {title}", v))
        assertEquals(
            listOf("Mother Mother", "O My Heart", "02 - Burning Pile"),
            renderTemplate("{artist}/{album}/{track} - {title}", v),
        )
        val missing = mapOf("artist" to "A", "title" to "T", "album" to "", "track" to "")
        assertEquals(listOf("A", "T"), renderTemplate("{artist}/{album}/{track} - {title}", missing))
    }

    @Test fun titles() {
        assertEquals("Burning Pile", cleanTitle("Burning Pile (Official Video)"))
        assertEquals("Song", cleanTitle("Song [Official Audio] (HD)"))
        assertEquals("Song (Live)", cleanTitle("Song (Live)"))
        assertEquals("Song" to listOf("A", "B"), extractFeatured("Song (feat. A & B)"))
    }

    @Test fun guess() {
        val a = guessArtistTitle("Mother Mother - Burning Pile (Official Video)", "MotherMotherVEVO")
        assertEquals("Mother Mother", a.artist)
        assertEquals("Burning Pile", a.title)
        val b = guessArtistTitle("Cool Song", "Some Artist - Topic")
        assertEquals("Some Artist", b.artist)
        assertEquals("Cool Song", b.title)
        val c = guessArtistTitle("Main x Guest - Track (feat. Other)", "")
        assertEquals("Main", c.artist)
        assertEquals("Track", c.title)
        assertEquals(listOf("Guest", "Other"), c.featured)
    }

    @Test fun similar() {
        assertTrue(similarity("Burning Pile", "burning pile!") > 0.95)
        assertTrue(similarity("Burning Pile", "Another Song") < 0.6)
        assertTrue(similarity("Música Ação", "musica acao") > 0.95)
    }
}

class LinksTest {
    @Test fun spotify() {
        val l = classify("https://open.spotify.com/intl-pt/track/4uLU6hMCjMI75M1A2tKUQC?si=abc")
        assertEquals("spotify_track", l.kind)
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", l.id)
        assertEquals("spotify_playlist", classify("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M").kind)
        assertEquals("spotify_album", classify("https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3").kind)
    }

    @Test fun youtube() {
        assertEquals("youtube_video", classify("https://www.youtube.com/watch?v=dQw4w9WgXcQ").kind)
        assertEquals("dQw4w9WgXcQ", classify("https://youtu.be/dQw4w9WgXcQ?t=10").id)
        assertEquals("youtube_video", classify("https://music.youtube.com/watch?v=dQw4w9WgXcQ").kind)
        assertEquals("youtube_playlist", classify("https://www.youtube.com/playlist?list=PLabc123").kind)
        assertEquals("dQw4w9WgXcQ", classify("https://www.youtube.com/shorts/dQw4w9WgXcQ").id)
    }

    @Test fun videoPlusList() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc123"
        assertEquals("youtube_video", classify(url).kind)
        assertEquals("youtube_playlist", classify(url, playlistIfBoth = true).kind)
        val mix = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ"
        assertEquals("youtube_video", classify(mix, playlistIfBoth = true).kind)
    }

    @Test fun searchAndUnsupported() {
        assertEquals("search", classify("Mother Mother - Burning Pile").kind)
        assertEquals("unsupported", classify("https://example.com/x").kind)
        val text = "https://youtu.be/dQw4w9WgXcQ https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC\n\nArtista - Música\n"
        assertEquals(3, parseInputs(text).size)
    }
}

class MatchingTest {
    private val meta = TrackMeta(title = "Burning Pile", artists = listOf("Mother Mother"), duration = 258.0, origin = "spotify")

    @Test fun prefersTopicWithRightDuration() {
        val cands = listOf(
            Candidate("1", "u1", "Mother Mother - Burning Pile (Live at the Roxy)", "Fan", 300.0),
            Candidate("2", "u2", "Burning Pile", "Mother Mother - Topic", 258.0),
            Candidate("3", "u3", "Burning Pile (Slowed + Reverb)", "Chill", 330.0),
        )
        assertEquals("2", pickBest(meta, cands).first?.id)
    }

    @Test fun rejectsUnrelated() {
        val cands = listOf(Candidate("x", "ux", "Totally different thing", "Someone", 30.0))
        assertNull(pickBest(meta, cands).first)
    }
}

class TaggerTest {
    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test fun writesId3v23AndKeepsAudio() {
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(100)
        // arquivo de entrada já com uma tag ID3v2.4 (como a do ffmpeg), que deve ser substituída
        val old = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4)
        val f = File.createTempFile("sonora", ".mp3")
        f.writeBytes(old + audio)

        val fields = TagFields(
            title = "Burning Pile", artist = "Mother Mother; Convidada Ação", album = "O My Heart",
            albumArtist = "Mother Mother", year = "2011", track = "2/12", disc = "1/1", genre = "Indie Rock",
            isrc = "", comment = "Baixado com Sonora", lyrics = "", sourceUrl = "https://youtu.be/xxxxxxxxxxx",
        )
        Tagger.write(f, fields, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))
        val out = f.readBytes()

        assertEquals("ID3", String(out.copyOfRange(0, 3), Charsets.US_ASCII))
        assertEquals(3, out[3].toInt())
        val size = ((out[6].toInt() and 0x7f) shl 21) or ((out[7].toInt() and 0x7f) shl 14) or
            ((out[8].toInt() and 0x7f) shl 7) or (out[9].toInt() and 0x7f)
        assertEquals(out.size - 10 - audio.size, size)
        assertArrayEquals(audio, out.copyOfRange(out.size - audio.size, out.size))
        assertNotNull(indexOf(out, "TIT2".toByteArray()).takeIf { it >= 0 })
        assertTrue(indexOf(out, "Burning Pile".toByteArray(Charsets.UTF_16LE)) > 0)
        assertTrue(indexOf(out, "Convidada Ação".toByteArray(Charsets.UTF_16LE)) > 0)
        assertTrue(indexOf(out, "APIC".toByteArray()) > 0)
        assertEquals(-1, indexOf(out, "USLT".toByteArray())) // letra vazia não gera frame
        f.delete()
    }
}
