package com.abplayer.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.abplayer.app.databinding.ActivityLibraryBinding
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.util.Locale

class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private val allBooks = mutableListOf<BookDisplayItem>()
    private val displayedBooks = mutableListOf<BookDisplayItem>()
    private lateinit var adapter: BooksAdapter

    private var selectedTab = 0
    private var searchQuery = ""
    private var currentActivePath: String? = null

    private val audioExtensions = setOf("mp3", "m4b", "m4a", "ogg", "flac", "wav", "aac", "opus")

    data class BookDisplayItem(
        val folderPath: String,
        val title: String,
        val chaptersCount: Int,
        val totalDurationMs: Long,
        val readMs: Long,
        val status: String,
        val coverPath: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        currentActivePath = prefs.getString("last_book_path", null)

        adapter = BooksAdapter(displayedBooks) { book ->
            val resultIntent = Intent().apply {
                putExtra("selected_book_path", book.folderPath)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        binding.rvBooks.layoutManager = LinearLayoutManager(this)
        binding.rvBooks.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { scanBooks() }
        binding.btnPickFolder.setOnClickListener {
            showFolderPickerDialog(Environment.getExternalStorageDirectory())
        }

        binding.btnSearchToggle.setOnClickListener {
            if (binding.etSearch.visibility == View.VISIBLE) {
                binding.etSearch.visibility = View.GONE
                binding.etSearch.setText("")
                searchQuery = ""
                filterBooks()
            } else {
                binding.etSearch.visibility = View.VISIBLE
                binding.etSearch.requestFocus()
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                filterBooks()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectedTab = tab?.position ?: 0
                filterBooks()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        scanBooks()
    }
    private fun showFolderPickerDialog(currentDir: File) {
        val dirs = currentDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val items = mutableListOf("✅ ВЫБРАТЬ ЭТУ ПАПКУ (${currentDir.name})")
        if (currentDir.parentFile != null && currentDir.parentFile!!.canRead()) {
            items.add("⬆ .. (Назад)")
        }
        dirs.forEach { items.add("📁 ${it.name}") }

        AlertDialog.Builder(this)
            .setTitle(currentDir.absolutePath)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
                        .edit().putString("root_folder", currentDir.absolutePath).apply()
                    scanBooks()
                } else if (items.get(which).startsWith("⬆")) {
                    currentDir.parentFile?.let { showFolderPickerDialog(it) }
                } else {
                    val dirName = items.get(which).removePrefix("📁 ")
                    showFolderPickerDialog(File(currentDir, dirName))
                }
            }
            .show()
    }

    private fun scanBooks() {
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val rootPath = prefs.getString("root_folder", null)
        if (rootPath == null) {
            Toast.makeText(this, "Выберите корневую папку с книгами", Toast.LENGTH_SHORT).show()
            showFolderPickerDialog(Environment.getExternalStorageDirectory())
            return
        }
        val rootDir = File(rootPath)
        if (!rootDir.exists() || !rootDir.isDirectory) return

        allBooks.clear()
        val foldersToScan = mutableListOf<File>()
        foldersToScan.add(rootDir)
        rootDir.listFiles()?.filter { it.isDirectory }?.forEach { foldersToScan.add(it) }

        val retriever = MediaMetadataRetriever()
        for (folder in foldersToScan) {
            val audioFiles = folder.listFiles()
                ?.filter { it.isFile && audioExtensions.contains(it.extension.lowercase()) }
                ?.sortedWith(compareBy({ it.name.length }, { it.name.lowercase() })) ?: continue

            if (audioFiles.isNotEmpty()) {
                val durations = audioFiles.map { file ->
                    try {
                        retriever.setDataSource(file.absolutePath)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } catch (_: Exception) { 0L }
                }
                val totalDur = durations.sum()
                val savedChap = prefs.getInt("chap_${folder.absolutePath}", 0).coerceIn(0, audioFiles.size - 1)
                val savedPos = prefs.getLong("pos_ms_${folder.absolutePath}", 0L)
                var readMs = 0L
                for (i in 0 until savedChap) readMs += durations.get(i)
                readMs += savedPos

                val status = prefs.getString("status_${folder.absolutePath}", "new") ?: "new"
                val cover = folder.listFiles()?.firstOrNull {
                    it.isFile && (it.extension.lowercase() == "jpg" || it.extension.lowercase() == "png")
                }?.absolutePath

                allBooks.add(BookDisplayItem(folder.absolutePath, folder.name, audioFiles.size, totalDur, readMs, status, cover))
            }
        }
        try { retriever.release() } catch (_: Exception) {}
        filterBooks()
    }

    private fun filterBooks() {
        displayedBooks.clear()
        for (book in allBooks) {
            val matchesTab = when (selectedTab) {
                1 -> book.status == "new"
                2 -> book.status == "started"
                3 -> book.status == "finished"
                else -> true
            }
            val matchesQuery = searchQuery.isEmpty() || book.title.lowercase().contains(searchQuery)
            if (matchesTab && matchesQuery) {
                displayedBooks.add(book)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun formatShortTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return if (h > 0) "${h}ч ${m}м" else "${m}м"
    }

    inner class BooksAdapter(
        private val items: List<BookDisplayItem>,
        private val onClick: (BookDisplayItem) -> Unit
    ) : RecyclerView.Adapter<BooksAdapter.BookViewHolder>() {

        inner class BookViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivCover: ImageView = view.findViewById(R.id.ivBookCover)
            val tvTitle: TextView = view.findViewById(R.id.tvItemTitle)
            val tvChapters: TextView = view.findViewById(R.id.tvItemChapters)
            val tvProgress: TextView = view.findViewById(R.id.tvItemProgress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
            return BookViewHolder(v)
        }

        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            val item = items.get(position)
            holder.tvTitle.text = item.title
            holder.tvTitle.setTextColor(if (item.folderPath == currentActivePath) Color.parseColor("#4DA6FF") else Color.WHITE)
            holder.tvChapters.text = "${item.chaptersCount} гл."

            val totalSafe = item.totalDurationMs.coerceAtLeast(1L)
            val pct = ((item.readMs * 100) / totalSafe).toInt().coerceIn(0, 100)
            holder.tvProgress.text = "${formatShortTime(item.readMs)} / ${formatShortTime(item.totalDurationMs)}   $pct%"

            if (item.coverPath != null) {
                holder.ivCover.setImageBitmap(BitmapFactory.decodeFile(item.coverPath))
            } else {
                holder.ivCover.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}