package com.downls10

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.webkit.CookieManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/** خطأ نهائي لا فائدة من إعادة المحاولة معه. */
class NonRetryableDownloadException(message: String) : IOException(message)

/** معلومات الاستجابة الفعلية للرابط بعد اتباع التحويلات (تُستخدم لمعرفة اسم الملف ونوعه). */
data class ProbeInfo(
    val finalUrl: String,
    val contentDisposition: String?,
    val contentType: String?,
    val contentLength: Long,
    val responseCode: Int
)

/** مصدر البيانات بعد فتح الاتصال (HTTP أو FTP). */
private class DownloadSource(
    val input: InputStream,
    val totalSize: Long,
    val startOffset: Long,
    private val closer: () -> Unit
) {
    fun close() {
        try { closer() } catch (_: Exception) { }
    }
}

class DownloadManagerEngine {

    private val executor = ThreadPoolExecutor(
        8,
        8,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue()
    )

    fun setMaxConcurrentDownloads(count: Int) {
        val value = count.coerceIn(1, 10)
        synchronized(executor) {
            if (value > executor.corePoolSize) {
                executor.maximumPoolSize = value
                executor.corePoolSize = value
            } else {
                executor.corePoolSize = value
                executor.maximumPoolSize = value
            }
            executor.prestartAllCoreThreads()
        }
    }

    /**
     * يمنح التنزيلات المستأنفة مسارات تنفيذ فورية حتى لا تبقى خلف تنزيل
     * آخر عندما يكون حد التزامن المحفوظ منخفضاً.
     */
    fun prepareImmediateResume() {
        val current = executor.corePoolSize
        if (current < 4) setMaxConcurrentDownloads(4)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeTasks = ConcurrentHashMap<Long, Boolean>()
    private val generations = ConcurrentHashMap<Long, Int>()
    // الاتصال الفعلي لكل تنزيل حتى يمكن إغلاقه فور الضغط على إيقاف.
    private val activeSources = ConcurrentHashMap<Long, DownloadSource>()

    private val bufferSize = 256 * 1024
    private val maxConsecutiveFailures = 12

    private val defaultUserAgent =
        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

    private fun isFtp(url: String) = url.startsWith("ftp://", ignoreCase = true) || url.startsWith("ftps://", ignoreCase = true)

    /** يفتح الرابط ويقرأ الترويسات فقط (دون تنزيل المحتوى). يرجع null عند الفشل أو لروابط FTP. */
    fun probe(url: String, userAgent: String?, referer: String?): ProbeInfo? {
        if (isFtp(url)) return null
        val temp = DownloadItem(
            id = 0L, url = url, fileName = "", extension = "",
            userAgent = userAgent, referer = referer
        )
        return try {
            val conn = openWithRedirects(temp, 0L)
            try {
                ProbeInfo(
                    finalUrl = conn.url.toString(),
                    contentDisposition = conn.getHeaderField("Content-Disposition"),
                    contentType = conn.contentType,
                    contentLength = conn.contentLengthLong,
                    responseCode = conn.responseCode
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * يرجع الحجم الإجمالي لمقاطع HLS بدل حجم ملف قائمة m3u8 الصغير.
     * إذا كانت القائمة الرئيسية يختار أعلى جودة معلنة، ثم يجمع أحجام ملفات المقاطع.
     * يرجع -1 عندما يتعذر معرفة الحجم بدل عرض حجم القائمة على أنه حجم الفيديو.
     */
    fun probeHlsTotalSize(url: String, userAgent: String?, referer: String?): Long {
        if (!url.contains(".m3u8", ignoreCase = true)) return -1L
        val item = DownloadItem(
            id = 0L, url = url, fileName = "", extension = "m3u8",
            userAgent = userAgent, referer = referer
        )
        return try {
            fun readPlaylist(playlistUrl: String): Pair<String, String>? {
                val connection = openWithRedirects(item, 0L, playlistUrl)
                return try {
                    if (connection.responseCode !in 200..299) return null
                    val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    Pair(connection.url.toString(), text)
                } finally {
                    connection.disconnect()
                }
            }

            var playlist = readPlaylist(url) ?: return -1L
            // إذا كانت Master Playlist، اختر أعلى معدل نقل معلن (أعلى جودة).
            if (playlist.second.contains("#EXT-X-STREAM-INF")) {
                val lines = playlist.second.lines()
                var bestBandwidth = -1L
                var bestUrl: String? = null
                for (index in lines.indices) {
                    val line = lines[index].trim()
                    if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                    val bandwidth = Regex("(?:AVERAGE-BANDWIDTH|BANDWIDTH)=(\\d+)")
                        .find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val next = lines.drop(index + 1).firstOrNull {
                        it.isNotBlank() && !it.trim().startsWith("#")
                    }?.trim() ?: continue
                    if (bandwidth > bestBandwidth) {
                        bestBandwidth = bandwidth
                        bestUrl = URL(URL(playlist.first), next).toString()
                    }
                }
                if (bestUrl != null) playlist = readPlaylist(bestUrl) ?: return -1L
            }

            val lines = playlist.second.lines()
            var total = 0L
            val segmentUrls = mutableListOf<String>()
            var pendingRangeLength = 0L
            for (lineValue in lines) {
                val line = lineValue.trim()
                if (line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true)) {
                    pendingRangeLength = line.substringAfter(':').substringBefore('@')
                        .toLongOrNull() ?: 0L
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    if (pendingRangeLength > 0L) {
                        total += pendingRangeLength
                        pendingRangeLength = 0L
                    } else {
                        segmentUrls.add(URL(URL(playlist.first), line).toString())
                    }
                }
            }
            if (segmentUrls.isEmpty()) return total.takeIf { it > 0L } ?: -1L

            // استعلم عن أحجام المقاطع بالتوازي لتجنب جمع حجم قائمة التشغيل فقط.
            val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
            try {
                val tasks = segmentUrls.map { segmentUrl ->
                    executor.submit<Long> {
                        var connection: HttpURLConnection? = null
                        try {
                            connection = (URL(segmentUrl).openConnection() as HttpURLConnection).apply {
                                requestMethod = "HEAD"
                                instanceFollowRedirects = true
                                connectTimeout = 5000
                                readTimeout = 5000
                                setRequestProperty("Accept-Encoding", "identity")
                                setRequestProperty("User-Agent", userAgent ?: defaultUserAgent)
                                setRequestProperty("Referer", playlist.first)
                                CookieManager.getInstance().getCookie(segmentUrl)?.let {
                                    setRequestProperty("Cookie", it)
                                }
                                connect()
                            }
                            if (connection.responseCode in 200..299) connection.contentLengthLong else -1L
                        } catch (_: Exception) {
                            -1L
                        } finally {
                            connection?.disconnect()
                        }
                    }
                }
                var measured = 0L
                var unknown = 0
                for (task in tasks) {
                    val size = try { task.get(6, java.util.concurrent.TimeUnit.SECONDS) }
                    catch (_: Exception) { task.cancel(true); -1L }
                    if (size > 0L) measured += size else unknown++
                }
                if (unknown == 0 && measured > 0L) measured + total
                else -1L
            } finally {
                executor.shutdownNow()
            }
        } catch (_: Exception) {
            -1L
        }
    }

    fun downloadFile(
        item: DownloadItem,
        saveDir: File,
        onProgress: (Int, String, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        downloadFileInternal(item, saveDir, null, onProgress, onComplete, onError)
    }

    fun downloadFileToUri(
        item: DownloadItem,
        contentResolver: ContentResolver,
        uri: Uri,
        onProgress: (Int, String, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        downloadFileInternal(item, null, Pair(contentResolver, uri), onProgress, onComplete, onError)
    }

    /**
     * يفتح الاتصال مع كوكيز المتصفح (WebView) والـ User-Agent والـ Referer.
     * التحويلات (redirects) تُتبع يدويًا، ولكل خطوة تُرسل كوكيز موقعها فقط،
     * حتى لا تصل كوكيز موقع إلى موقع آخر.
     * ولصفحات الوسيط (تحذير الملفات الكبيرة في Drive، صفحة MediaFire) يستخرج الرابط الحقيقي.
     */
    private fun openWithRedirects(item: DownloadItem, existingLength: Long, urlOverride: String? = null): HttpURLConnection {
        // حوّل روابط المشاركة المعروفة إلى رابط تنزيل مباشر قبل طلب الشبكة.
        var currentUrl = urlOverride ?: LinkResolver.resolve(item.url)
        var redirects = 0
        var htmlHops = 0
        var confirmedFile = false
        val ua = item.userAgent
        // استخدم صفحة بدء التنزيل كمرجع للطلب الأول، ثم عنوان كل تحويل للطلب التالي.
        // خوادم MajorGeeks تتحقق من Referer وترفض أحياناً الرابط المباشر إذا لم يأتِ من موقعها.
        val initialHost = try { URL(currentUrl).host.lowercase() } catch (_: Exception) { "" }
        var requestReferer = item.referer
        if (requestReferer.isNullOrBlank() &&
            (initialHost == "majorgeeks.com" || initialHost == "www.majorgeeks.com" ||
             initialHost == "m.majorgeeks.com" || initialHost.endsWith(".majorgeeks.com"))) {
            requestReferer = "https://www.majorgeeks.com/"
        }
        while (true) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 60000
            conn.readTimeout = 300000
            conn.instanceFollowRedirects = false
            // بدون ضغط gzip حتى يكون حجم الملف وتقدّم التنزيل ومواضع الاستئناف دقيقة.
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            conn.setRequestProperty("User-Agent", if (!ua.isNullOrBlank()) ua else defaultUserAgent)

            // CookieManager يعيد فقط الكوكيز المطابقة لنطاق هذا العنوان؛ لذلك نطلبها
            // لكل خطوة تحويل، بما فيها روابط CDN وروابط التنزيل المحوّلة من Drive.
            val cookies = try { CookieManager.getInstance().getCookie(currentUrl) } catch (_: Exception) { null }
            if (!cookies.isNullOrBlank()) conn.setRequestProperty("Cookie", cookies)

            // أرسل Referer للطلب الأول كما وصل من WebView، ثم عنوان التحويل السابق.
            // لا نرسل Referer من HTTPS إلى HTTP لتجنب تسريب عنوان الصفحة عبر اتصال غير مشفر.
            val isHttpsDowngrade = requestReferer?.startsWith("https://", ignoreCase = true) == true &&
                currentUrl.startsWith("http://", ignoreCase = true)
            if (!requestReferer.isNullOrBlank() && !isHttpsDowngrade) {
                conn.setRequestProperty("Referer", requestReferer)
            }

            val needsHtml = LinkResolver.needsHtmlResolution(currentUrl)
            // صفحات الوسيط تُطلب بدون Range أولاً، ثم نعيد الطلب مع Range عندما نتأكد أنه الملف نفسه
            if (existingLength > 0 && (!needsHtml || confirmedFile)) {
                conn.setRequestProperty("Range", "bytes=$existingLength-")
            }
            conn.connect()

            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                redirects++
                if (location.isNullOrBlank() || redirects > 10) {
                    throw NonRetryableDownloadException("تحويلات كثيرة")
                }
                val previousUrl = currentUrl
                currentUrl = URL(URL(currentUrl), location).toString()
                requestReferer = previousUrl
                continue
            }

            if (needsHtml && !confirmedFile && code == 200) {
                val contentType = conn.contentType ?: ""
                val isHtml = contentType.startsWith("text/html", ignoreCase = true) &&
                    conn.getHeaderField("Content-Disposition") == null
                if (isHtml) {
                    val html = try {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } catch (e: Exception) {
                        ""
                    }
                    conn.disconnect()
                    htmlHops++
                    val next = if (htmlHops <= 3) LinkResolver.resolveFromHtml(currentUrl, html.take(400_000)) else null
                    if (next == null) {
                        throw NonRetryableDownloadException("تعذّر الحصول على رابط التنزيل المباشر (قد يكون الملف خاصاً أو غير متاح)")
                    }
                    requestReferer = currentUrl
                    currentUrl = next
                    continue
                }
                // هذا هو الملف نفسه: إن كنا نستأنف نعيد الطلب مع Range
                if (existingLength > 0) {
                    conn.disconnect()
                    confirmedFile = true
                    continue
                }
            }
            return conn
        }
    }

    /** مصدر HTTP: يعالج رفض الاستئناف (416) وأكواد الأخطاء. */
    private fun openHttpSource(item: DownloadItem, existingLength: Long, urlOverride: String? = null): DownloadSource {
        var conn: HttpURLConnection = openWithRedirects(item, existingLength, urlOverride)
        var effectiveExisting = existingLength
        if (conn.responseCode == 416 && existingLength > 0) {
            // الخادم يرفض نقطة الاستئناف (الملف تغيّر أو اكتمل): نبدأ من الصفر
            conn.disconnect()
            effectiveExisting = 0L
            conn = openWithRedirects(item, 0L, urlOverride)
        }
        var finalConn = conn
        var code = finalConn.responseCode

        // عند رفض رابط التنزيل الذي حوّله التطبيق (403)، جرّب رابط المشاركة الأصلي مرة واحدة.
        // هذا يفيد المواقع التي تتطلب المرور بصفحة المشاركة أولاً، ولا يتجاوز تسجيل الدخول أو صلاحيات الموقع.
        if (code == 403 && urlOverride == null) {
            val originalUrl = item.url
            val resolvedUrl = LinkResolver.resolve(originalUrl)
            if (resolvedUrl != originalUrl) {
                finalConn.disconnect()
                val fallback = openWithRedirects(item, effectiveExisting, originalUrl)
                val fallbackCode = fallback.responseCode
                if (fallbackCode == HttpURLConnection.HTTP_OK ||
                    fallbackCode == HttpURLConnection.HTTP_PARTIAL
                ) {
                    finalConn = fallback
                    code = fallbackCode
                } else {
                    fallback.disconnect()
                }
            }
        }

        // يتم تنزيل المحتوى كما أرسله الخادم مهما كان امتداده أو MIME، ما دام الرد صالحاً.
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            finalConn.disconnect()
            if (code == 401) throw NonRetryableDownloadException("الرابط يحتاج تسجيل دخول أو صلاحية")
            if (code == 403) throw NonRetryableDownloadException("رفض الموقع التنزيل (403): جرّب فتح الرابط في المتصفح وتسجيل الدخول، ثم أعد التنزيل. قد يمنع الموقع برامج التنزيل الخارجية")
            if (code == 404) throw NonRetryableDownloadException("الملف غير موجود (404) أو أن رابط التنزيل انتهت صلاحيته")
            if (code == 410) throw NonRetryableDownloadException("رابط الملف انتهت صلاحيته (410)")
            if (code == 416) throw NonRetryableDownloadException("تعذر استئناف الملف؛ أعد التنزيل من البداية")
            if (code == 429 || code >= 500) throw IOException("خطأ مؤقت من الخادم: $code")
            throw NonRetryableDownloadException("رفض الخادم الطلب (HTTP $code)")
        }
        var startOffset = effectiveExisting
        if (effectiveExisting > 0 && code == HttpURLConnection.HTTP_OK) {
            // الخادم تجاهل Range: نعيد الملف من البداية بدل إضافة بايتات مكررة
            startOffset = 0L
        } else if (code == HttpURLConnection.HTTP_PARTIAL) {
            // لا نضيف البيانات الجزئية إلا إذا أكد الخادم أن بدايتها هي موضع الاستئناف المطلوب.
            val range = finalConn.getHeaderField("Content-Range") ?: ""
            val actualStart = Regex("""bytes\s+(\d+)-\d+/(?:\d+|\*)""", RegexOption.IGNORE_CASE)
                .find(range)?.groupValues?.get(1)?.toLongOrNull()
            if (actualStart == null || actualStart != effectiveExisting) {
                finalConn.disconnect()
                if (effectiveExisting > 0L) {
                    return openHttpSource(item, 0L, urlOverride)
                }
                throw NonRetryableDownloadException("استجابة تنزيل جزئي غير صالحة من الخادم")
            }
        }
        val contentLength = finalConn.contentLengthLong.coerceAtLeast(0L)
        val totalSize = if (code == HttpURLConnection.HTTP_PARTIAL) startOffset + contentLength else contentLength
        return DownloadSource(finalConn.inputStream, totalSize, startOffset) { finalConn.disconnect() }
    }

    /**
     * روابط تحتاج طلب واجهة لمعرفة الرابط المباشر (Mega، Yandex Disk، pCloud).
     * يُطلب الرابط من جديد عند كل محاولة لأن الروابط المباشرة مؤقتة.
     * ملفات Mega تُفك تشفيرها أثناء القراءة.
     */
    private fun openRemoteSource(item: DownloadItem, existingLength: Long): DownloadSource {
        val link = LinkResolver.resolveRemote(item.url)
        val src = openHttpSource(item, existingLength, link.directUrl)
        val key = link.megaKey ?: return src
        val decrypted = MegaDecryptInputStream(src.input, key, src.startOffset)
        return DownloadSource(decrypted, src.totalSize, src.startOffset) { src.close() }
    }

    /** مصدر FTPS مشفّر: يدعم مستخدم:كلمة_مرور في الرابط، وإلا دخولاً مجهولاً آمناً. */
    private fun openFtpSource(item: DownloadItem, existingLength: Long): DownloadSource {
        val uri = Uri.parse(item.url)
        val host = uri.host ?: throw NonRetryableDownloadException("رابط FTP غير صالح")
        val port = if (uri.port > 0) uri.port else 21
        val path = uri.path
        if (path.isNullOrEmpty() || path == "/") throw NonRetryableDownloadException("رابط FTP لا يحدد ملفاً")

        var user = "anonymous"
        var password = "anonymous@example.com"
        val userInfo = uri.userInfo
        if (!userInfo.isNullOrEmpty()) {
            user = Uri.decode(userInfo.substringBefore(':'))
            password = if (userInfo.contains(':')) Uri.decode(userInfo.substringAfter(':')) else ""
        }

        val client = FtpClient(host, port)
        try {
            client.connect(user, password)
            val size = client.size(path)
            val (stream, actualOffset) = client.retrieve(path, existingLength)
            val total = if (size > 0) size else 0L
            return DownloadSource(stream, total, actualOffset) { client.close() }
        } catch (e: Exception) {
            client.close()
            throw e
        }
    }

    /** الحجم الفعلي للملف المكتوب (المصدر الوحيد الموثوق لنقطة الاستئناف). */
    private fun uriLength(resolver: ContentResolver, uri: Uri): Long {
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun checkDiskSpace(totalSize: Long, startOffset: Long, checkDir: File) {
        if (totalSize <= 0L) return
        val needed = totalSize - startOffset
        val available = try {
            StatFs(checkDir.path).availableBytes
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
        if (needed > available) throw NonRetryableDownloadException("لا توجد مساحة كافية على الجهاز")
    }

    private fun downloadFileInternal(
        item: DownloadItem,
        saveDir: File?,
        uriTarget: Pair<ContentResolver, Uri>?,
        onProgress: (Int, String, Long, Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        activeTasks[item.id] = true
        item.state = DownloadState.DOWNLOADING
        // رقم لهذه المحاولة: إذا بدأت محاولة أحدث لنفس التنزيل تتوقف هذه تلقائياً (لا يكتب خيطان في ملف واحد)
        val generation = (generations[item.id] ?: 0) + 1
        generations[item.id] = generation
        val stopped = { activeTasks[item.id] == false || generations[item.id] != generation }

        executor.execute {
            var failures = 0
            while (true) {
                var source: DownloadSource? = null
                var output: OutputStream? = null
                var fileOutput: RandomAccessFile? = null
                var completedOk = false
                var retry = false
                try {
                    val existingLength = if (saveDir != null) {
                        val outputFile = File(saveDir, item.fileName)
                        if (outputFile.exists()) outputFile.length() else 0L
                    } else {
                        // حجم الملف الفعلي وليس الرقم المحفوظ (قد يكون أقدم إذا أُغلق التطبيق فجأة)
                        uriLength(uriTarget!!.first, uriTarget.second)
                    }

                    val src = when {
                        isFtp(item.url) -> openFtpSource(item, existingLength)
                        LinkResolver.needsRemoteResolution(item.url) -> openRemoteSource(item, existingLength)
                        else -> openHttpSource(item, existingLength)
                    }
                    source = src
                    activeSources[item.id] = src
                    val startOffset = src.startOffset
                    val totalSize = src.totalSize
                    item.totalBytes = totalSize
                    item.downloadedBytes = startOffset
                    val dirToCheck = saveDir ?: File(Environment.getExternalStoragePublicDirectory(""), "DownLS10")
                    checkDiskSpace(totalSize, startOffset, dirToCheck)

                    if (saveDir != null) {
                        val outputFile = File(saveDir, item.fileName)
                        fileOutput = RandomAccessFile(outputFile, "rw")
                        if (startOffset == 0L) fileOutput.setLength(0L) else fileOutput.seek(startOffset)
                    } else {
                        val mode = if (startOffset > 0L) "wa" else "w"
                        output = uriTarget!!.first.openOutputStream(uriTarget.second, mode)
                            ?: throw NonRetryableDownloadException("تعذّر فتح ملف التنزيل للكتابة")
                    }

                    val input = src.input
                    val data = ByteArray(bufferSize)
                    var totalRead = startOffset
                    var lastTime = System.currentTimeMillis()
                    var bytesSinceLast = 0L
                    var madeProgress = false
                    while (true) {
                        if (stopped()) return@execute
                        val count = input.read(data)
                        if (count == -1) break
                        if (count == 0) continue

                        if (fileOutput != null) fileOutput.write(data, 0, count)
                        else output!!.write(data, 0, count)

                        totalRead += count
                        bytesSinceLast += count
                        item.downloadedBytes = totalRead
                        if (!madeProgress) {
                            madeProgress = true
                            failures = 0   // وصلت بيانات جديدة: نبدأ عدّ الإخفاقات من جديد
                        }

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastTime
                        if (elapsed >= 4000) {
                            val speedKb = (bytesSinceLast * 1000L / elapsed) / 1024L
                            val speedText = if (speedKb >= 1024) "${speedKb / 1024} MB/s" else "$speedKb KB/s"
                            val progress = if (totalSize > 0) ((totalRead.toDouble() * 100.0) / totalSize.toDouble()).toInt().coerceIn(0, 100) else 0
                            mainHandler.post { onProgress(progress, speedText, totalRead, totalSize) }
                            lastTime = now
                            bytesSinceLast = 0L
                        }
                    }

                    // إذا انقطع الاتصال قبل اكتمال الحجم المعلن فهذا ليس نجاحًا
                    if (totalSize > 0 && totalRead < totalSize) {
                        throw java.io.EOFException("انقطع الاتصال قبل اكتمال الملف")
                    }

                    item.downloadedBytes = totalRead
                    // If the server did not send Content-Length, record the real final size.
                    if (totalSize <= 0L) item.totalBytes = totalRead
                    item.state = DownloadState.COMPLETED
                    completedOk = true   // نستدعي onComplete بعد إغلاق الملف في finally

                } catch (e: Exception) {
                    if (stopped()) return@execute
                    // أخطاء الشبكة المؤقتة فقط (انقطاع، مهلة، إعادة ضبط الاتصال...) نعيد فيها المحاولة
                    val retryable = e is IOException &&
                        e !is NonRetryableDownloadException &&
                        e !is MalformedURLException &&
                        e !is SSLHandshakeException
                    if (retryable && failures < maxConsecutiveFailures) {
                        retry = true
                    } else {
                        item.state = DownloadState.ERROR
                        mainHandler.post { onError(e.message ?: "فشل التحميل") }
                    }
                } finally {
                    try { fileOutput?.close() } catch (_: Exception) { }
                    try { output?.close() } catch (_: Exception) { }
                    source?.close()
                    activeSources.remove(item.id, source)
                    if (completedOk) mainHandler.post { onComplete() }
                }

                if (retry) {
                    failures++
                    val waitMs = minOf(30000L, 2000L * (1L shl minOf(failures, 4)))
                    try { Thread.sleep(waitMs) } catch (_: InterruptedException) { return@execute }
                    if (stopped()) return@execute
                    continue
                }
                return@execute
            }
        }
    }

    fun pauseDownload(id: Long) {
        activeTasks[id] = false
        // إغلاق الاتصال يفك read() فوراً بدل انتظار مهلة الشبكة.
        activeSources.remove(id)?.close()
    }

    fun cancelDownload(id: Long, saveDir: File, fileName: String, deleteFileOnDisk: Boolean) {
        pauseDownload(id)
        if (deleteFileOnDisk) {
            val file = File(saveDir, fileName)
            if (file.exists()) file.delete()
        }
    }
}
