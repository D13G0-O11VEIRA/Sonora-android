package app.sonora

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val bitrate: String = "320",
    val filenameTemplate: String = "{artist} - {title}",
    val playlistSubfolder: Boolean = true,
    val embedCover: Boolean = true,
    val enrichOnline: Boolean = true,
    val fetchLyrics: Boolean = true,
    val skipExisting: Boolean = true,
    val concurrent: Int = 2,
    val artistSeparator: String = "; ",
    val watchListIsPlaylist: Boolean = false,
)

val BITRATES = listOf("128", "192", "256", "320")

val TEMPLATE_PRESETS = listOf(
    "Artista - Título" to "{artist} - {title}",
    "Artista / Álbum / Nº - Título" to "{artist}/{album}/{track} - {title}",
    "Álbum / Nº - Artista - Título" to "{album}/{track} - {artist} - {title}",
    "Somente o título" to "{title}",
    "Nº da playlist - Artista - Título" to "{index} - {artist} - {title}",
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("sonora_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> get() = _settings

    private fun load(): Settings {
        val d = Settings()
        val bitrate = prefs.getString("bitrate", d.bitrate) ?: d.bitrate
        return Settings(
            bitrate = if (bitrate in BITRATES) bitrate else d.bitrate,
            filenameTemplate = (prefs.getString("template", d.filenameTemplate) ?: d.filenameTemplate)
                .ifBlank { d.filenameTemplate },
            playlistSubfolder = prefs.getBoolean("playlistSubfolder", d.playlistSubfolder),
            embedCover = prefs.getBoolean("embedCover", d.embedCover),
            enrichOnline = prefs.getBoolean("enrichOnline", d.enrichOnline),
            fetchLyrics = prefs.getBoolean("fetchLyrics", d.fetchLyrics),
            skipExisting = prefs.getBoolean("skipExisting", d.skipExisting),
            concurrent = prefs.getInt("concurrent", d.concurrent).coerceIn(1, 4),
            artistSeparator = (prefs.getString("artistSeparator", d.artistSeparator) ?: d.artistSeparator)
                .ifEmpty { d.artistSeparator },
            watchListIsPlaylist = prefs.getBoolean("watchListIsPlaylist", d.watchListIsPlaylist),
        )
    }

    fun save(s: Settings) {
        val fixed = s.copy(
            filenameTemplate = s.filenameTemplate.ifBlank { Settings().filenameTemplate },
            artistSeparator = s.artistSeparator.ifEmpty { "; " },
            concurrent = s.concurrent.coerceIn(1, 4),
        )
        prefs.edit()
            .putString("bitrate", fixed.bitrate)
            .putString("template", fixed.filenameTemplate)
            .putBoolean("playlistSubfolder", fixed.playlistSubfolder)
            .putBoolean("embedCover", fixed.embedCover)
            .putBoolean("enrichOnline", fixed.enrichOnline)
            .putBoolean("fetchLyrics", fixed.fetchLyrics)
            .putBoolean("skipExisting", fixed.skipExisting)
            .putInt("concurrent", fixed.concurrent)
            .putString("artistSeparator", fixed.artistSeparator)
            .putBoolean("watchListIsPlaylist", fixed.watchListIsPlaylist)
            .apply()
        _settings.value = fixed
    }
}
