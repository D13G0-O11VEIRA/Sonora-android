package app.sonora

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.io.IOException

class LibraryItem(val uriString: String, val title: String, val artist: String, val album: String, val durationMs: Long)

/** Salva em Música/Sonora usando o MediaStore (não precisa de permissão de armazenamento no Android 10+). */
object MediaSaver {
    const val ROOT = "Music/Sonora"

    fun relativePath(subdirs: List<String>): String =
        if (subdirs.isEmpty()) ROOT else ROOT + "/" + subdirs.joinToString("/")

    fun exists(ctx: Context, relPath: String, displayName: String): Boolean {
        val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(displayName, "$relPath/")
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            sel,
            args,
            null,
        )?.use { return it.count > 0 }
        return false
    }

    fun save(ctx: Context, src: File, relPath: String, displayName: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Não foi possível criar o arquivo em $relPath")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Não foi possível gravar o arquivo")
            out.use { o -> src.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Lista as músicas da pasta Música/Sonora que este app pode ver. */
    fun list(ctx: Context): List<LibraryItem> {
        val out = mutableListOf<LibraryItem>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            proj,
            sel,
            arrayOf("$ROOT%"),
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                out.add(
                    LibraryItem(
                        uriString = uri.toString(),
                        title = c.getString(1).orEmpty(),
                        artist = c.getString(2).orEmpty().let { if (it == "<unknown>") "" else it },
                        album = c.getString(3).orEmpty().let { if (it == "Sonora") "" else it },
                        durationMs = c.getLong(4),
                    ),
                )
            }
        }
        return out
    }
}
