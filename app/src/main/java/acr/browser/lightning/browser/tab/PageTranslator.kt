package acr.browser.lightning.browser.tab

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * DownLS10 page translation implementation, adapted to Lightning's tab package.
 * Translates visible page text in-place without navigating away or forcing RTL.
 */
object PageTranslator {
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val MAX_TEXT_NODES = 500
    private const val TIMEOUT_SECONDS = 25L

    private const val EXTRACT_JS = """
        (function() {
            var texts = [];
            if (!document.body) return JSON.stringify(texts);
            var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
                acceptNode: function(node) {
                    var p = node.parentNode;
                    if (!p) return NodeFilter.FILTER_REJECT;
                    var tag = p.tagName ? p.tagName.toLowerCase() : '';
                    if (tag === 'script' || tag === 'style' || tag === 'noscript' || tag === 'textarea') return NodeFilter.FILTER_REJECT;
                    if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
                    return NodeFilter.FILTER_ACCEPT;
                }
            });
            var n;
            while (texts.length < 500 && (n = walker.nextNode())) texts.push(n.nodeValue);
            return JSON.stringify(texts);
        })();
    """

    fun translatePage(webView: WebView, targetLang: String = "ar", onDone: (Boolean, String) -> Unit) {
        if (webView.url.isNullOrBlank()) {
            onDone(false, "لا توجد صفحة مفتوحة للترجمة")
            return
        }
        webView.evaluateJavascript(EXTRACT_JS) { raw ->
            try {
                val json = if (raw == null || raw == "null") "[]" else {
                    val value = raw.trim()
                    if (value.startsWith("\"") && value.endsWith("\"")) JSONTokener(value).nextValue() as String else value
                }
                val array = JSONArray(json)
                val original = (0 until array.length().coerceAtMost(MAX_TEXT_NODES)).map { array.getString(it) }
                if (original.isEmpty()) {
                    onDone(false, "تعذّر استخراج نص من الصفحة (قد تكون فارغة أو لم تكتمل)")
                    return@evaluateJavascript
                }
                val chunks = mutableListOf<List<String>>()
                var chunk = mutableListOf<String>()
                var length = 0
                original.forEach { text ->
                    if (length + text.length + 5 > 1800 && chunk.isNotEmpty()) {
                        chunks.add(chunk)
                        chunk = mutableListOf()
                        length = 0
                    }
                    chunk.add(text)
                    length += text.length + 5
                }
                if (chunk.isNotEmpty()) chunks.add(chunk)

                val results = ConcurrentHashMap<Int, List<String>?>()
                val errors = ConcurrentHashMap<Int, String>()
                val latch = CountDownLatch(chunks.size)
                chunks.forEachIndexed { index, texts ->
                    executor.execute {
                        var error = "فشل الاتصال بخدمة الترجمة"
                        results[index] = translateChunk(texts, targetLang) { error = it }
                        if (results[index] == null) errors[index] = error
                        latch.countDown()
                    }
                }
                Thread {
                    try { latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    mainHandler.post {
                        val translated = mutableListOf<String>()
                        var failure: String? = null
                        chunks.indices.forEach { index ->
                            val result = results[index]
                            if (result == null) {
                                failure = failure ?: errors[index] ?: "فشل بالترجمة"
                                translated.addAll(chunks[index])
                            } else translated.addAll(result)
                        }
                        if (failure != null) onDone(false, failure!!)
                        else if (translated.size != original.size) onDone(false, "اختلاف عدد النصوص بعد الترجمة، حاول مجدداً")
                        else applyTranslations(webView, translated, onDone)
                    }
                }.start()
            } catch (e: Exception) {
                onDone(false, "تعذّر تحليل محتوى الصفحة: ${e.message}")
            }
        }
    }

    private fun translateChunk(texts: List<String>, targetLang: String, onError: (String) -> Unit): List<String>? {
        val joined = texts.joinToString("\n@@@\n")
        val translated = translateRaw(joined, targetLang, "translate.googleapis.com", onError)
            ?: translateRaw(joined, targetLang, "translate.google.com", onError)
            ?: return null
        val parts = translated.split("\n@@@\n", "@@@")
        return if (parts.size == texts.size) parts else translated.split("\n").takeIf { it.size == texts.size } ?: texts
    }

    private fun translateRaw(text: String, targetLang: String, host: String, onError: (String) -> Unit): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://$host/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=" + URLEncoder.encode(text, "UTF-8"))
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                onError("رفضت خدمة الترجمة الطلب (رمز الخطأ: $code)")
                return null
            }
            val body = StringBuilder()
            BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) body.append(line)
            }
            val segments = JSONArray(body.toString()).getJSONArray(0)
            buildString {
                for (i in 0 until segments.length()) append(segments.getJSONArray(i).optString(0))
            }
        } catch (_: java.net.UnknownHostException) {
            onError("تعذّر الوصول لخدمة الترجمة - تأكد من اتصال الإنترنت")
            null
        } catch (_: java.net.SocketTimeoutException) {
            onError("انتهت مهلة الاتصال بخدمة الترجمة")
            null
        } catch (e: Exception) {
            onError("خطأ بالاتصال: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun applyTranslations(webView: WebView, translated: List<String>, onDone: (Boolean, String) -> Unit) {
        val json = JSONArray(translated).toString()
        val script = """
            (function() {
                var translated = $json, idx = 0;
                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
                    acceptNode: function(node) {
                        var p = node.parentNode;
                        if (!p) return NodeFilter.FILTER_REJECT;
                        var tag = p.tagName ? p.tagName.toLowerCase() : '';
                        if (tag === 'script' || tag === 'style' || tag === 'noscript' || tag === 'textarea') return NodeFilter.FILTER_REJECT;
                        if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
                        return NodeFilter.FILTER_ACCEPT;
                    }
                });
                var n;
                while (n = walker.nextNode()) {
                    if (idx < translated.length) n.nodeValue = translated[idx];
                    idx++;
                }
                return true;
            })();
        """
        try {
            webView.evaluateJavascript(script) { result ->
                if (result == "true") onDone(true, "تمت الترجمة بنجاح")
                else onDone(false, "تعذّر تطبيق الترجمة على الصفحة، حاول مجدداً")
            }
        } catch (_: Exception) {
            onDone(false, "تعذّر تطبيق الترجمة على الصفحة، حاول مجدداً")
        }
    }
}
