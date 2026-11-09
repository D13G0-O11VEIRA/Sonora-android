package app.sonora

import java.util.concurrent.atomic.AtomicBoolean

enum class Status(val label: String) {
    QUEUED("Na fila"),
    RESOLVING("Buscando fonte"),
    DOWNLOADING("Baixando"),
    CONVERTING("Convertendo"),
    TAGGING("Gravando metadados"),
    DONE("Concluído"),
    SKIPPED("Já existe"),
    ERROR("Erro"),
    CANCELLED("Cancelado");

    val finished: Boolean
        get() = this == DONE || this == SKIPPED || this == ERROR || this == CANCELLED
}

/** Metadados de uma faixa (mesmos campos da versão Python). */
class TrackMeta(
    var title: String = "",
    var artists: List<String> = emptyList(),
    var album: String = "",
    var albumArtist: String = "",
    var year: String = "",
    var trackNo: Int = 0,
    var trackTotal: Int = 0,
    var discNo: Int = 0,
    var discTotal: Int = 0,
    var genre: String = "",
    var isrc: String = "",
    var duration: Double = 0.0,
    var coverUrl: String = "",
    var thumbUrl: String = "",
    var sourceUrl: String = "",
    var youtubeUrl: String = "",
    var youtubeId: String = "",
    var spotifyId: String = "",
    var origin: String = "youtube", // youtube | spotify | search
    var infoLoaded: Boolean = false,
    var playlistName: String = "",
    var playlistIndex: Int = 0,
) {
    val mainArtist: String
        get() = artists.firstOrNull() ?: ""
}

class Resolved(
    val tracks: List<TrackMeta>,
    val name: String = "",
    val kind: String = "single", // single | album | playlist
)

class Batch(val name: String, val kind: String)

class DlJob(val id: Int, val meta: TrackMeta, val batch: Batch?) {
    @Volatile var status: Status = Status.QUEUED
    @Volatile var progress: Float = 0f
    @Volatile var speed: String = ""
    @Volatile var error: String = ""
    @Volatile var outputName: String = ""
    val cancelled = AtomicBoolean(false)
    val processId: String get() = "sonora-job-$id"

    fun toUi(): JobUi {
        val sub = listOf(meta.artists.joinToString(", "), meta.album).filter { it.isNotBlank() }.joinToString(" • ")
        return JobUi(
            id = id,
            title = meta.title.ifBlank { meta.youtubeUrl.ifBlank { meta.sourceUrl.ifBlank { "…" } } },
            subtitle = sub,
            status = status,
            progress = progress,
            speed = speed,
            error = error,
            cover = meta.coverUrl.ifBlank { meta.thumbUrl },
        )
    }
}

/** Foto imutável de um job para a interface. */
data class JobUi(
    val id: Int,
    val title: String,
    val subtitle: String,
    val status: Status,
    val progress: Float,
    val speed: String,
    val error: String,
    val cover: String,
) {
    val active: Boolean get() = !status.finished
}

class CancelledByUser : Exception("Cancelado")
