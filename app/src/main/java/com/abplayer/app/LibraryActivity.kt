package com.abplayer.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Job
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.abplayer.app.databinding.ActivityLibraryBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private val allBooks = mutableListOf<BookDisplayItem>()
    private val displayedBooks = mutableListOf<BookDisplayItem>()
    private lateinit var adapter: BooksAdapter
    private var scanJob: Job? = null

    // Кэш обложек, чтобы не декодировать одно и то же при прокрутке
    private val coverCache = LruCache<String, Bitmap>(12)

    private var selectedTab = 0
    private var searchQuery = ""
    private var currentActivePath: String? = null

    private val audioExtensions = setOf("mp3", "m4b", "m4a", "ogg", "flac", "wav", "aac", "opus")

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            try {
                contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
                    .edit().putString("root_tree_uri", treeUri.toString()).apply()
                scanBooks()
            } catch (_: SecurityException) {
                Toast.makeText(this, "Не удалось сохранить доступ к папке", Toast.LENGTH_LONG).show()
            }
        }
    }

    data class BookDisplayItem(
        val folderUri: String,
        val stateKey: String,
        val title: String,
        val chaptersCount: Int,
        val totalDurationMs: Long,
        val readMs: Long,
        val status: String,
        val coverUri: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        currentActivePath = prefs.getString("last_book_uri", null)

        adapter = BooksAdapter(displayedBooks) { book ->
            val resultIntent = Intent().apply {
                putExtra("selected_book_uri", book.folderUri)
                putExtra("selected_book_key", book.stateKey)
                putExtra("selected_book_title", book.title)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        binding.rvBooks.layoutManager = LinearLayoutManager(this)
        binding.rvBooks.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { scanBooks() }
        binding.btnPickFolder.setOnClickListener { folderPickerLauncher.launch(null) }

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

    override fun onDestroy() {
        scanJob?.cancel()
        super.onDestroy()
    }

    /** Точка входа: проверяет доступ, показывает прогресс, уводит тяжёлую работу в IO. */
    private fun scanBooks() {
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val treeUri = prefs.getString("root_tree_uri", null)?.let(Uri::parse)

        if (treeUri == null) {
            Toast.makeText(this, "Выберите корневую папку с книгами", Toast.LENGTH_SHORT).show()
            binding.root.post { folderPickerLauncher.launch(null) }   // НЕ из стека onCreate
            return
        }

        val rootDir = DocumentFile.fromTreeUri(this, treeUri)
        if (rootDir == null || !rootDir.isDirectory || !rootDir.canRead()) {
            Toast.makeText(this, "Нет доступа к папке. Выберите её заново", Toast.LENGTH_LONG).show()
            binding.root.post { folderPickerLauncher.launch(null) }
            return
        }

        scanJob?.cancel()
        setScanning(true)

        scanJob = lifecycleScope.launch {
            try {
                val books = withContext(Dispatchers.IO) { buildBooksList(rootDir, treeUri, prefs) }
                allBooks.clear()
                allBooks.addAll(books)
                filterBooks()
            } catch (e: Exception) {
                Toast.makeText(this@LibraryActivity, "Ошибка сканирования: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setScanning(false)
            }
        }
    }

    private fun setScanning(active: Boolean) {
        // ProgressBar с id progressBarScan должен быть добавлен в activity_library.xml
        runCatching { binding.progressBarScan.isVisible = active }
    }

    /** Выполняется на Dispatchers.IO — никакого обращения к UI отсюда. */
    private suspend fun buildBooksList(
        rootDir: DocumentFile,
        treeUri: Uri,
        prefs: android.content.SharedPreferences
    ): List<BookDisplayItem> {
        ensureUiAlive()

        val legacyRootPath = prefs.getString("root_folder", null)
            ?.takeIf { oldPath -> oldPath.trimEnd('/') == pathForTreeUri(treeUri)?.trimEnd('/') }

        val result = mutableListOf<BookDisplayItem>()

        val foldersToScan = mutableListOf<DocumentFile>()
        foldersToScan.add(rootDir)
        rootDir.listFiles()
            .filter { it.isDirectory && !it.name.orEmpty().startsWith(".") }
            .forEach { foldersToScan.add(it) }

        for (folder in foldersToScan) {
            ensureUiAlive()

            val children = folder.listFiles()
            val audioFiles = children
                .filter { it.isFile && audioExtensions.contains(it.name.orEmpty().substringAfterLast('.', "").lowercase()) }
                .sortedWith(compareBy({ it.name.orEmpty().length }, { it.name.orEmpty().lowercase() }))

            if (audioFiles.isEmpty()) continue

            // Один retriever на папку, обязательно освобождаем в finally
            val durations = ArrayList<Long>(audioFiles.size)
            val retriever = MediaMetadataRetriever()
            try {
                for (file in audioFiles) {
                    ensureUiAlive()
                    durations += try {
                        retriever.setDataSource(this, file.uri)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } catch (_: Exception) {
                        0L
                    }
                }
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            val totalDur = durations.sum()
            val uriKey = folder.uri.toString()
            val stateKey = when {
                legacyRootPath == null -> uriKey
                folder.uri == treeUri -> legacyRootPath
                else -> "$legacyRootPath/${folder.name}"
            }

            // Безопасное ограничение: coerceAtLeast/coerceAtMost не падают при size == 0
            val savedChap = prefs.getInt("chap_$uriKey", prefs.getInt("chap_$stateKey", 0))
                .coerceAtLeast(0)
                .coerceAtMost(audioFiles.size - 1)

            val savedPos = prefs.getLong("pos_ms_$uriKey", prefs.getLong("pos_ms_$stateKey", 0L))
            var readMs = 0L
            for (i in 0 until savedChap) readMs += durations[i]
            readMs += savedPos.coerceAtLeast(0L)

            val status = prefs.getString("status_$uriKey", prefs.getString("status_$stateKey", "new")) ?: "new"

            val cover = children.firstOrNull {
                it.isFile && it.name.orEmpty().substringBeforeLast('.').lowercase().let { base ->
                    base == "cover" || base == "folder"
                } && it.name.orEmpty().substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png")
            } ?: children.firstOrNull {
                it.isFile && it.name.orEmpty().substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png")
            }

            result.add(
                BookDisplayItem(
                    uriKey, stateKey, folder.name ?: "Книга",
                    audioFiles.size, totalDur, readMs, status, cover?.uri?.toString()
                )
            )
        }
        return result
    }

    /** Прерывает фоновое сканирование, если Activity уже уничтожена. */
    private suspend fun ensureUiAlive() {
        if (isFinishing || isDestroyed) throw kotlinx.coroutines.CancellationException("activity destroyed")
        kotlinx.coroutines.yield()
    }

    private fun pathForTreeUri(treeUri: Uri): String? {
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val volume = documentId.substringBefore(':')
        val relativePath = documentId.substringAfter(':', "").trim('/')
        val volumeRoot = if (volume == "primary") Environment.getExternalStorageDirectory().absolutePath else "/storage/$volume"
        return if (relativePath.isEmpty()) volumeRoot else "$volumeRoot/$relativePath"
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
            if (matchesTab && matchesQuery) displayedBooks.add(book)
        }
        adapter.notifyDataSetChanged()
    }

    private fun formatShortTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return if (h > 0) "${h}ч ${m}м" else "${m}м"
    }

    private inner class BooksAdapter(
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
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvTitle.setTextColor(if (item.folderUri == currentActivePath) Color.parseColor("#4DA6FF") else Color.WHITE)
            holder.tvChapters.text = "${item.chaptersCount} гл."

            val totalSafe = item.totalDurationMs.coerceAtLeast(1L)
            val pct = ((item.readMs * 100) / totalSafe).toInt().coerceIn(0, 100)
            holder.tvProgress.text = "${formatShortTime(item.readMs)} / ${formatShortTime(item.totalDurationMs)}   $pct%"

            loadCover(holder, item.coverUri)
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size

        /** Декодирование обложки вне главного потока + защита от подмены позиции. */
        private fun loadCover(holder: BookViewHolder, coverUri: String?) {
            val tag = coverUri ?: ""
            holder.ivCover.tag = tag
            holder.ivCover.setImageResource(android.R.drawable.ic_menu_gallery)

            if (coverUri == null) return

            coverCache.get(coverUri)?.let { cached ->
                if (holder.ivCover.tag == tag) holder.ivCover.setImageBitmap(cached)
                return
            }

            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(Uri.parse(coverUri))?.use { input ->
                            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                            BitmapFactory.decodeStream(input, null, opts)
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bmp != null) coverCache.put(coverUri, bmp)
                if (isActive && holder.ivCover.tag == tag && bmp != null) {
                    holder.ivCover.setImageBitmap(bmp)
                }
            }
        }
    }
}