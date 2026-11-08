package com.downls10

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream

/**
 * عميل FTPS صريح: يشترط TLS لتشفير بيانات الدخول وقناة التنزيل.
 * لا يعود إلى FTP غير المشفر إذا لم يدعم الخادم TLS.
 */
class FtpClient(private val host: String, private val port: Int) {

    init {
        require(host.isNotBlank() && host.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "اسم خادم FTP غير صالح"
        }
        require(port in 1..65535) { "منفذ FTP غير صالح" }
    }

    private var client: FTPSClient? = null

    private fun connected(): FTPSClient =
        client ?: throw IOException("اتصال FTPS غير متاح")

    private fun validateArgument(value: String, label: String) {
        if (value.length > 4096 || value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            throw IOException("$label غير صالح")
        }
    }

    fun connect(user: String, password: String) {
        validateArgument(user, "اسم المستخدم")
        validateArgument(password, "كلمة المرور")

        val ftps = FTPSClient(false)
        // تحقق من شهادة الخادم واسم المضيف؛ لا تتجاوز أخطاء الشهادة.
        ftps.setEndpointCheckingEnabled(true)
        ftps.connectTimeout = 20000
        ftps.defaultTimeout = 30000
        ftps.dataTimeout = java.time.Duration.ofSeconds(30)

        try {
            ftps.connect(host, port)
            if (!FTPReply.isPositiveCompletion(ftps.replyCode)) {
                throw IOException("رفض خادم FTPS الاتصال")
            }
            ftps.execPBSZ(0)
            ftps.execPROT("P")
            if (!ftps.login(user, password)) {
                throw NonRetryableDownloadException("فشل تسجيل الدخول الآمن إلى خادم FTPS")
            }
            if (!ftps.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw IOException("تعذّر ضبط وضع النقل الثنائي")
            }
            ftps.enterLocalPassiveMode()
            client = ftps
        } catch (e: Exception) {
            try { ftps.disconnect() } catch (_: Exception) { }
            if (e is NonRetryableDownloadException) throw e
            throw IOException(
                "تعذّر إنشاء اتصال FTPS آمن. يجب أن يدعم الخادم TLS وشهادة صحيحة؛ لن يُستخدم FTP غير المشفر.",
                e
            )
        }
    }

    /** حجم الملف أو -1 إذا لم يقدّم الخادم الحجم. */
    fun size(path: String): Long {
        validateArgument(path, "مسار الملف")
        val ftps = connected()
        val code = ftps.sendCommand("SIZE", path)
        if (code == 213) {
            return ftps.replyString.substringAfter(' ').trim().toLongOrNull() ?: -1L
        }
        return -1L
    }

    /**
     * يبدأ التنزيل من الموضع offset. لا يتم قبول الاستئناف إلا إذا وافق الخادم على REST.
     */
    fun retrieve(path: String, offset: Long): Pair<InputStream, Long> {
        validateArgument(path, "مسار الملف")
        val ftps = connected()
        var actualOffset = 0L
        if (offset > 0L) ftps.setRestartOffset(offset)

        var stream = ftps.retrieveFileStream(path)
        if (stream == null && offset > 0L) {
            // إذا رفض الخادم الاستئناف، أعد الطلب من بداية الملف.
            ftps.setRestartOffset(0L)
            stream = ftps.retrieveFileStream(path)
            actualOffset = 0L
        } else if (stream != null && offset > 0L) {
            actualOffset = offset
        }

        stream ?: throw NonRetryableDownloadException("تعذّر جلب الملف عبر FTPS (${ftps.replyCode})")
        val code = ftps.replyCode
        if (code != 125 && code != 150) {
            try { stream.close() } catch (_: Exception) { }
            throw NonRetryableDownloadException("رفض خادم FTPS تنزيل الملف ($code)")
        }
        return Pair(stream, actualOffset)
    }

    fun close() {
        val ftps = client
        client = null
        if (ftps != null) {
            try { ftps.logout() } catch (_: Exception) { }
            try { ftps.disconnect() } catch (_: Exception) { }
        }
    }
}
