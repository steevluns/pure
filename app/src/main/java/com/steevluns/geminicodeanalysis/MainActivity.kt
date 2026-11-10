package com.steevluns.geminicodeanalysis

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var chatBox: TextView
    private lateinit var promptBox: EditText
    private lateinit var scroll: ScrollView
    private val history = JSONArray()
    private val extractedFiles = linkedMapOf<String, String>()
    private val prefs by lazy { getSharedPreferences("gemini_settings", Context.MODE_PRIVATE) }

    private val textExtensions = setOf(
        "bat", "c", "cfg", "conf", "cpp", "cs", "css", "go", "h", "html",
        "ini", "java", "js", "json", "jsx", "md", "php", "ps1", "py", "rb",
        "rs", "sh", "toml", "ts", "tsx", "txt", "xml", "yaml", "yml"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        if (prefs.getString("api_key", null).isNullOrBlank()) {
            showApiKeyDialog()
        } else {
            addMessage("Gemini Code Analysis", "ارفع ملف ZIP أو ملفات كود، أو اكتب سؤالك للبدء.")
        }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 18, 16, 12)
            setBackgroundColor(0xFFF4F7FB.toInt())
        }

        val title = TextView(this).apply {
            text = "✨ Gemini Code Analysis"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF183153.toInt())
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 14)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val uploadButton = Button(this).apply {
            text = "رفع ملفات"
            setOnClickListener { chooseFiles() }
        }
        val keyButton = Button(this).apply {
            text = "مفتاح API"
            setOnClickListener { showApiKeyDialog() }
        }
        val resetButton = Button(this).apply {
            text = "محادثة جديدة"
            setOnClickListener { resetChat() }
        }
        toolbar.addView(uploadButton, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(keyButton, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(resetButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(toolbar)

        scroll = ScrollView(this)
        chatBox = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF202B3C.toInt())
            setPadding(12, 12, 12, 12)
            setTextIsSelectable(true)
        }
        scroll.addView(chatBox, ViewGroup.LayoutParams(-1, -2))
        val scrollParams = LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = 10
            bottomMargin = 10
        }
        root.addView(scroll, scrollParams)

        promptBox = EditText(this).apply {
            hint = "اكتب سؤالك عن الكود..."
            textSize = 15f
            minLines = 2
            maxLines = 5
            setPadding(12, 10, 12, 10)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(promptBox, LinearLayout.LayoutParams(-1, -2))

        val sendButton = Button(this).apply {
            text = "إرسال إلى Gemini"
            setOnClickListener { sendPrompt() }
        }
        root.addView(sendButton, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun addMessage(who: String, message: String) {
        chatBox.append("\n\n$who\n$message\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun showApiKeyDialog() {
        val field = EditText(this).apply {
            hint = "Google Gemini API Key"
            setSingleLine(true)
            inputType = 0x00000081
            setText(prefs.getString("api_key", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("إعداد مفتاح Gemini API")
            .setMessage("أدخل مفتاح Google AI Studio. يُحفظ على هذا الهاتف فقط.")
            .setView(field)
            .setPositiveButton("حفظ") { _, _ ->
                val key = field.text.toString().trim()
                if (key.isNotEmpty()) {
                    prefs.edit().putString("api_key", key).apply()
                    addMessage("النظام", "تم حفظ مفتاح API على الجهاز.")
                } else {
                    Toast.makeText(this, "أدخل مفتاح API صالحاً", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun chooseFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, 410)
    }

    @Deprecated("Deprecated by Android, retained for compatibility with Android 8+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 410 || resultCode != Activity.RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } ?: data.data?.let { uris.add(it) }

        var count = 0
        var totalChars = 0
        try {
            for (uri in uris) {
                val name = queryName(uri) ?: "uploaded_file"
                val lower = name.lowercase()
                if (lower.endsWith(".zip")) {
                    val result = readZip(uri)
                    result.forEach { (file, text) ->
                        if (totalChars + text.length <= 5_000_000) {
                            extractedFiles["$name/$file"] = text
                            totalChars += text.length
                            count++
                        }
                    }
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in textExtensions && totalChars < 5_000_000) {
                        val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText().take(1_000_000) } ?: ""
                        extractedFiles[name] = text
                        totalChars += text.length
                        count++
                    }
                }
            }
            addMessage("النظام", "تم استخراج $count ملف نصي. أصبحت الملفات ضمن سياق التحليل.")
            if (count == 0) addMessage("تنبيه", "لم يتم العثور على ملفات نصية مدعومة داخل الملفات المحددة.")
        } catch (e: Exception) {
            addMessage("خطأ في قراءة الملفات", e.message ?: "تعذر فتح الملف.")
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment
    }

    private fun readZip(uri: Uri): Map<String, String> {
        val output = linkedMapOf<String, String>()
        var totalBytes = 0
        contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && output.size < 300 && totalBytes < 5_000_000) {
                    if (!entry.isDirectory) {
                        val ext = entry.name.substringAfterLast('.', "").lowercase()
                        if (ext in textExtensions) {
                            val bytes = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var entryBytes = 0
                            while (true) {
                                val n = zip.read(buffer)
                                if (n <= 0) break
                                entryBytes += n
                                totalBytes += n
                                if (entryBytes > 1_000_000 || totalBytes > 5_000_000) break
                                bytes.write(buffer, 0, n)
                            }
                            if (entryBytes <= 1_000_000 && totalBytes <= 5_000_000) {
                                output[entry.name] = bytes.toString("UTF-8")
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return output
    }

    private fun sendPrompt() {
        val prompt = promptBox.text.toString().trim()
        if (prompt.isEmpty()) return
        val key = prefs.getString("api_key", null)
        if (key.isNullOrBlank()) {
            showApiKeyDialog()
            Toast.makeText(this, "أدخل مفتاح Gemini API أولاً", Toast.LENGTH_LONG).show()
            return
        }
        promptBox.setText("")
        addMessage("أنت", prompt)
        val sendButtonMessage = "جارٍ الاتصال بـ Gemini..."
        addMessage("النظام", sendButtonMessage)

        executor.execute {
            try {
                val contextText = if (extractedFiles.isNotEmpty() && history.length() == 0) {
                    buildString {
                        append("You are an expert code analysis assistant. Analyze the uploaded project files.\n")
                        extractedFiles.forEach { (name, text) ->
                            append("\n--- FILE: ").append(name).append(" ---\n")
                            append(text).append("\n")
                        }
                        append("\n--- END OF PROJECT FILES ---\n")
                    }
                } else ""
                val actualPrompt = if (contextText.isNotEmpty()) "$contextText\nUser question: $prompt" else prompt
                val contents = JSONArray()
                for (i in 0 until history.length()) contents.put(history.getJSONObject(i))
                val userContent = JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", actualPrompt)))
                contents.put(userContent)

                val body = JSONObject()
                    .put("contents", contents)
                    .put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 8192))

                val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent?key=$key")
                val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30000
                    readTimeout = 120000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                val responseCode: Int
                val responseText: String
                try {
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    responseCode = connection.responseCode
                    val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                    responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                } finally {
                    connection.disconnect()
                }

                if (responseCode !in 200..299) {
                    val error = try { JSONObject(responseText).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    throw Exception(error ?: "Gemini API returned HTTP $responseCode")
                }
                val root = JSONObject(responseText)
                val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val answer = buildString {
                    if (parts != null) for (i in 0 until parts.length()) {
                        append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                    }
                }.ifBlank { "لم يصل رد نصي من Gemini." }

                history.put(userContent)
                history.put(JSONObject().put("role", "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", answer))))

                runOnUiThread { addMessage("Gemini", answer) }
            } catch (e: Exception) {
                runOnUiThread { addMessage("خطأ", e.message ?: "تعذر الاتصال بالخدمة.") }
            }
        }
    }

    private fun resetChat() {
        while (history.length() > 0) history.remove(history.length() - 1)
        extractedFiles.clear()
        chatBox.text = ""
        addMessage("النظام", "تم بدء محادثة جديدة ومسح الملفات المرفوعة.")
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
