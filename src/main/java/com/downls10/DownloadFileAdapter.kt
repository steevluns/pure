package com.downls10

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView
import java.io.File

class DownloadFileAdapter(
    private val context: Context,
    private val items: List<DownloadFileItem>,
    private val onAddClick: (DownloadFileItem) -> Unit,
    private val onDownloadClick: (DownloadFileItem) -> Unit,
    private val onCancelClick: (DownloadFileItem) -> Unit
) : BaseAdapter() {

    fun setItems(newItems: List<DownloadFileItem>) {
        // Need to replace the underlying list
        // This is a simple implementation - in a real app you might want to use mutableListOf
        throw UnsupportedOperationException("Use constructor with new items")
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_download_file, parent, false)
        val item = items[position]

        val tvFileIcon = view.findViewById<TextView>(R.id.tvFileIcon)
        val tvFileName = view.findViewById<TextView>(R.id.tvFileName)
        val tvFileSize = view.findViewById<TextView>(R.id.tvFileSize)
        val tvFileExtension = view.findViewById<TextView>(R.id.tvFileExtension)
        val btnAddFile = view.findViewById<Button>(R.id.btnAddFile)
        val btnDownloadFile = view.findViewById<Button>(R.id.btnDownloadFile)
        val btnCancelFile = view.findViewById<Button>(R.id.btnCancelFile)

        // Set file information
        tvFileName.text = item.fileName
        tvFileIcon.text = getIconForExtension(item.extension)

        // Format file size
        val formattedSize = formatFileSize(item.fileSize)
        val sizeText = if (item.downloadedSize > 0) {
            "${formatFileSize(item.downloadedSize)} (${formattedSize})"
        } else {
            formattedSize
        }
        tvFileSize.text = sizeText

        tvFileExtension.text = ".${item.extension}"

        // Set button click listeners
        btnAddFile.setOnClickListener {
            onAddClick(item)
        }

        btnDownloadFile.setOnClickListener {
            onDownloadClick(item)
        }

        btnCancelFile.setOnClickListener {
            onCancelClick(item)
        }

        return view
    }

    /** يعرض حجم الملف بوحدة مناسبة (KB / MB / GB) */
    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L * 1024L) return (bytes / 1024L).toString() + " KB"
        val mb = bytes / (1024.0 * 1024.0)
        if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun getIconForExtension(ext: String): String {
        return when (ext.lowercase()) {
            "mp4", "mkv", "avi" -> "🎬"
            "mp3", "wav" -> "🎵"
            "apk" -> "📱"
            "pdf", "doc", "txt" -> "📄"
            "zip", "rar" -> "📦"
            "jpg", "png" -> "🖼️"
            else -> "📁"
        }
    }
}

data class DownloadFileItem(
    val fileName: String,
    val extension: String,
    val fileSize: Long,
    val downloadedSize: Long = 0L,
    val filePath: String = ""
)
