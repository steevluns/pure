package acr.browser.lightning.downloadmanager

import acr.browser.lightning.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class DownloadAdapter(
    private val context: Context,
    private val items: List<DownloadItem>,
    private var showSpeed: Boolean,
    private val onPauseResumeClick: (DownloadItem) -> Unit,
    private val onDeleteClick: (DownloadItem) -> Unit,
    private val onMoreClick: (DownloadItem) -> Unit
) : BaseAdapter() {
    fun setShowSpeed(visible: Boolean) {
        showSpeed = visible
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int): DownloadItem = items[position]
    override fun getItemId(position: Int) = items[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_download, parent, false)
        val item = items[position]
        val icon = view.findViewById<TextView>(R.id.tvFileIcon)
        val name = view.findViewById<TextView>(R.id.tvFileName)
        val size = view.findViewById<TextView>(R.id.tvFileSize)
        val status = view.findViewById<TextView>(R.id.tvStatus)
        val speed = view.findViewById<TextView>(R.id.tvSpeed)
        val pause = view.findViewById<Button>(R.id.btnPauseResume)
        val delete = view.findViewById<Button>(R.id.btnDelete)
        val more = view.findViewById<Button>(R.id.btnMore)

        name.text = item.fileName
        icon.text = when (item.extension.lowercase()) {
            "mp4", "mkv", "avi" -> "🎬"
            "mp3", "wav" -> "🎵"
            "apk" -> "📱"
            "pdf", "doc", "txt" -> "📄"
            "zip", "rar" -> "📦"
            "jpg", "png" -> "🖼️"
            else -> "📁"
        }
        status.text = item.status
        val total = if (item.totalBytes > 0L || item.state == DownloadState.COMPLETED) formatBytes(item.totalBytes) else "الحجم غير معروف"
        size.text = "${formatBytes(item.downloadedBytes)} / $total"
        speed.visibility = if (showSpeed) View.VISIBLE else View.GONE
        if (showSpeed) speed.text = item.speed

        when (item.state) {
            DownloadState.DOWNLOADING -> { pause.visibility = View.VISIBLE; pause.text = "إيقاف" }
            DownloadState.PAUSED -> { pause.visibility = View.VISIBLE; pause.text = "استئناف" }
            DownloadState.COMPLETED -> pause.visibility = View.GONE
            DownloadState.ERROR -> { pause.visibility = View.VISIBLE; pause.text = "إعادة" }
        }
        pause.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> { onPauseResumeClick(item); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        delete.setOnClickListener { onDeleteClick(item) }
        more.setOnClickListener { onMoreClick(item) }
        view.setOnLongClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("رابط التنزيل", item.url))
            Toast.makeText(context, "تم نسخ رابط التنزيل", Toast.LENGTH_SHORT).show()
            true
        }
        return view
    }

    private fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        if (value < 1024L) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB", "EB")
        var amount = value.toDouble() / 1024.0
        var unit = 0
        while (amount >= 1024.0 && unit < units.lastIndex) { amount /= 1024.0; unit++ }
        return String.format(java.util.Locale.US, if (amount < 10.0) "%.2f %s" else "%.1f %s", amount, units[unit])
    }
}
