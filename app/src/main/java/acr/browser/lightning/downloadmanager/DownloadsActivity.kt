package acr.browser.lightning.downloadmanager

import acr.browser.lightning.R
import acr.browser.lightning.DefaultBrowserActivity
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** واجهة مدير التنزيلات DownLS10 مع اختصار للعودة إلى متصفح Lightning. */
class DownloadsActivity : Activity() {
    private var showSpeed = true
    private lateinit var adapter: DownloadAdapter
    private val refreshListener: () -> Unit = {
        runOnUiThread {
            if (::adapter.isInitialized) {
                adapter.notifyDataSetChanged()
                updateDownloadCount()
            }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            if (!isFinishing && ::adapter.isInitialized) {
                DownloadsRepository.restoreListIfEmpty(this@DownloadsActivity)
                adapter.notifyDataSetChanged()
                updateDownloadCount()
                handler.postDelayed(this, 3000L)
            }
        }
    }

    private fun prefs() = getSharedPreferences("main_prefs", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downls10_manager)
        showSpeed = prefs().getBoolean("showSpeed", true)
        requestStoragePermissionOnce()
        DownloadsRepository.ensureLoaded(this)

        adapter = DownloadAdapter(
            this,
            DownloadsRepository.downloadList,
            showSpeed,
            onPauseResumeClick = { handlePauseResume(it) },
            onDeleteClick = { showDeleteDialog(it) },
            onMoreClick = { showDownloadItemMenu(it) }
        )
        findViewById<ListView>(R.id.listView).adapter = adapter
        findViewById<View>(R.id.btnAddUrl).setOnClickListener { showAddUrlDialog() }
        findViewById<View>(R.id.btnOpenBrowser).setOnClickListener {
            val browserIntent = Intent(this, DefaultBrowserActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(browserIntent)
            finish()
        }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showOptionsMenu() }
        findViewById<View>(R.id.btnDeleteCompleted).setOnClickListener { deleteCompleted() }
        findViewById<View>(R.id.btnDeleteAllDownloads).setOnClickListener { deleteAll() }
        updateDownloadCount()
    }

    override fun onResume() {
        super.onResume()
        DownloadsRepository.ensureLoaded(this)
        DownloadsRepository.restoreListIfEmpty(this)
        DownloadsRepository.addListener(refreshListener)
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        updateDownloadCount()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onPause() {
        DownloadsRepository.removeListener(refreshListener)
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun requestStoragePermissionOnce() {
        if (prefs().getBoolean("storagePermissionAsked", false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            prefs().edit().putBoolean("storagePermissionAsked", true).apply()
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            prefs().edit().putBoolean("storagePermissionAsked", true).apply()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 4102)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4102) prefs().edit().putBoolean("storagePermissionAsked", true).apply()
    }

    private fun updateDownloadCount() {
        findViewById<TextView>(R.id.tvDownloadCount)?.text =
            "عدد الملفات: ${DownloadsRepository.downloadList.size}"
    }

    private fun deleteCompleted() {
        DownloadsRepository.downloadList.filter { it.state == DownloadState.COMPLETED }.toList().forEach {
            DownloadsRepository.deleteDownload(this, it, deleteFileOnDisk = false)
        }
        adapter.notifyDataSetChanged()
        updateDownloadCount()
    }

    private fun deleteAll() {
        DownloadsRepository.downloadList.toList().forEach {
            DownloadsRepository.deleteDownload(this, it, deleteFileOnDisk = false)
        }
        adapter.notifyDataSetChanged()
        updateDownloadCount()
    }

    private fun showAddUrlDialog() {
        val input = EditText(this).apply { hint = "إلصق رابط التنزيل هنا..." }
        AlertDialog.Builder(this)
            .setTitle("إضافة تنزيل جديد")
            .setView(input)
            .setPositiveButton("تنزيل") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) DownloadsRepository.startNewDownload(this, url, showToast = false)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showOptionsMenu() {
        val options = arrayOf(
            if (showSpeed) "إخفاء سرعة التنزيل" else "إظهار سرعة التنزيل",
            "التنزيلات المتزامنة",
            "السجل (History)"
        )
        AlertDialog.Builder(this).setTitle("الخيارات").setItems(options) { _, which ->
            when (which) {
                0 -> {
                    showSpeed = !showSpeed
                    prefs().edit().putBoolean("showSpeed", showSpeed).apply()
                    adapter.setShowSpeed(showSpeed)
                }
                1 -> showConcurrentDownloadsDialog()
                2 -> showHistoryDialog()
            }
        }.show()
    }

    private fun showConcurrentDownloadsDialog() {
        val values = (1..10).toList()
        val current = DownloadsRepository.getMaxConcurrentDownloads(this)
        AlertDialog.Builder(this)
            .setTitle("التنزيلات المتزامنة")
            .setSingleChoiceItems(values.map { it.toString() }.toTypedArray(), current - 1) { dialog, which ->
                DownloadsRepository.setMaxConcurrentDownloads(this, values[which])
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun copyToClipboard(value: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("رابط", value))
        Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
    }

    private fun showHistoryDialog() {
        val history = DownloadHistory.get(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "سجل التنزيلات فارغ", Toast.LENGTH_SHORT).show()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }
        history.forEach { entry ->
            val row = TextView(this).apply {
                text = entry.url
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 20, 0, 20)
                setOnClickListener { copyToClipboard(entry.url) }
                setOnLongClickListener { copyToClipboard(entry.url); true }
            }
            container.addView(row)
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#333333"))
            })
        }
        AlertDialog.Builder(this).setTitle("سجل التنزيلات").setView(scroll)
            .setPositiveButton("مسح السجل") { _, _ -> DownloadHistory.clear(this) }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun handlePauseResume(item: DownloadItem) {
        if (item.state == DownloadState.DOWNLOADING) DownloadsRepository.pauseDownload(this, item)
        else if (item.state == DownloadState.PAUSED || item.state == DownloadState.ERROR) DownloadsRepository.resumeDownload(this, item)
    }

    private fun showDeleteDialog(item: DownloadItem) {
        val options = arrayOf("الحذف من القائمة فقط", "الحذف النهائي من الجهاز")
        AlertDialog.Builder(this).setTitle("حذف التنزيل").setItems(options) { _, which ->
            DownloadsRepository.deleteDownload(this, item, deleteFileOnDisk = which == 1)
        }.show()
    }

    private fun showDownloadItemMenu(item: DownloadItem) {
        AlertDialog.Builder(this).setItems(arrayOf("إعادة تسمية", "نسخ رابط الصفحة")) { _, which ->
            when (which) {
                0 -> showRenameDownloadDialog(item)
                1 -> {
                    val pageUrl = item.referer?.trim().orEmpty()
                    if (pageUrl.isBlank()) Toast.makeText(this, "رابط الصفحة غير متوفر لهذا التنزيل", Toast.LENGTH_SHORT).show()
                    else copyToClipboard(pageUrl)
                }
            }
        }.show()
    }

    private fun showRenameDownloadDialog(item: DownloadItem) {
        val input = EditText(this).apply {
            setText(item.fileName)
            setSelection(text.length)
            hint = "اسم الملف الجديد"
        }
        AlertDialog.Builder(this).setTitle("إعادة تسمية الملف").setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) Toast.makeText(this, "اكتب اسماً صالحاً", Toast.LENGTH_SHORT).show()
                else if (DownloadsRepository.renameDownloadFile(this, item, name)) {
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "تمت إعادة تسمية الملف", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "تعذرت إعادة التسمية. تحقق من الاسم أو حالة الملف", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null).show()
    }
}