package acr.browser.lightning.downloadmanager
import android.content.Context
object DownLS10DownloadBridge {
    fun start(context: Context, url: String, userAgent: String?, referer: String?, mimeType: String?) {
        DownloadsRepository.startNewDownload(
            context = context, url = url, userAgent = userAgent,
            referer = referer, mimeType = mimeType, showToast = true
        )
    }
}
