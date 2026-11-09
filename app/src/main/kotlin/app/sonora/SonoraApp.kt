package app.sonora

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.concurrent.thread

class SonoraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        // A primeira execução extrai o Python e o ffmpeg embutidos (alguns segundos).
        thread(name = "ytdl-init") {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                Yt.ready.complete(Unit)
                Engine.log("Motor de download pronto.")
            } catch (e: Exception) {
                Yt.ready.completeExceptionally(e)
                Engine.log("Falha ao iniciar o yt-dlp/ffmpeg: ${e.message}")
                Engine.notice.value = "Falha ao iniciar o motor de download: ${e.message}"
            }
        }
    }
}
