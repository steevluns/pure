package acr.browser.lightning.download

import acr.browser.lightning.downloadmanager.DownloadsRepository
import android.app.Application
import javax.inject.Inject

/**
 * Routes every browser download to the integrated DownLS10 download manager.
 * No Android DownloadManager-backed browser downloader is used.
 */
class DelegatingFileDownloader @Inject constructor(
    private val application: Application
) : FileDownloader {

    override suspend fun download(pendingDownload: PendingDownload) {
        DownloadsRepository.confirmAndStartDownload(
            context = application,
            url = pendingDownload.url,
            userAgent = pendingDownload.userAgent,
            mimeType = pendingDownload.mimeType
        )
    }
}
