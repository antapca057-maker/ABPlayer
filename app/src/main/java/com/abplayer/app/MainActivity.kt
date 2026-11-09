package com.abplayer.app

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.appcompat.app.AppCompatActivity
import com.abplayer.app.databinding.ActivityMainBinding
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.PlaybackParameters
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var playerService: PlayerService? = null
    private var isBound = false

    private var isLocked = false
    private var playbackSpeed = 1.0f
    private var boostGainMb = 0
    private var eqPresetIndex = 0
    private var id3Mode = 0 // 0 = Имя файла, 1 = ID3 UTF-8, 2 = ID3 Windows-1251

    private var currentBook: BookItem? = null
    private var currentChapterIndex = 0
    private var positionHistory = mutableListOf<String>()

    private val handler = Handler(Looper.getMainLooper())
    private var sleepTimer: CountDownTimer? = null
    private var sleepRemainingMs = 0L
    private var lastSleepDurationMs = 0L
    private lateinit var sensorManager: SensorManager
    private var lastShakeTime = 0L

    private val audioExtensions = setOf("mp3", "m4b", "m4a", "ogg", "flac", "wav", "aac", "opus")
    private val buttonKeys = arrayOf("show_sleep", "show_audio", "show_chapters", "show_speed", "show_characters", "show_history", "show_lock")
    private val id3Labels = arrayOf("ID3: Файл", "ID3: UTF-8", "ID3: Win-1251")

    data class ChapterItem(val uri: Uri, val name: String, val durationMs: Long, val rawTitle: String?)
    data class BookItem(val folderUri: Uri, val stateKey: String, val title: String, val chapters: List<ChapterItem>, val totalDurationMs: Long, val coverUri: Uri?)

    private val libraryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.getStringExtra("selected_book_uri")
            if (uri != null) {
                val bookUri = Uri.parse(uri)
                loadSingleBook(
                    bookUri,
                    result.data?.getStringExtra("selected_book_key") ?: uri,
                    result.data?.getStringExtra("selected_book_title"),
                    autoPlay = true
                )
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlayerService.LocalBinder
            playerService = binder.getService()
            isBound = true

            playerService?.onChapterEndedCallback = {
                val book = currentBook
                if (book != null && currentChapterIndex + 1 < book.chapters.size) {
                    playChapter(currentChapterIndex + 1, 0L, true)
                } else if (book != null) {
                    getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
                        .edit().putString("status_${book.stateKey}", "finished").apply()
                    binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                }
            }
            playerService?.onStateChangedCallback = {
                runOnUiThread {
                    val isPlaying = playerService?.exoPlayer?.isPlaying == true
                    binding.btnPlayPause.setImageResource(
                        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                    )
                    applyAudioEffects()
                    updateProgressUI()
                }
            }
            syncWithRunningServiceOrLoadSaved()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playerService = null
            isBound = false
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createNotificationChannel()
        val serviceIntent = Intent(this, PlayerService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        playbackSpeed = prefs.getFloat("playback_speed", 1.0f)
        id3Mode = prefs.getInt("id3_mode", 0).coerceIn(0, 2)
        binding.btnSpeed.text = String.format(Locale.US, "%.1fx", playbackSpeed)
        binding.btnId3Mode.text = id3Labels.get(id3Mode)
        applyToolbarVisibility()
        refreshChapterTitleDisplay()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        saveBookState()
        handler.removeCallbacks(updateRunnable)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("abplayer_channel", "Воспроизведение книг", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupListeners() {
        binding.btnLibrary.setOnClickListener {
            libraryLauncher.launch(Intent(this, LibraryActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Переключение режимов ID3: Имя файла -> UTF-8 -> Windows-1251
        binding.btnId3Mode.setOnClickListener {
            id3Mode = (id3Mode + 1) % 3
            getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE).edit().putInt("id3_mode", id3Mode).apply()
            binding.btnId3Mode.text = id3Labels.get(id3Mode)
            refreshChapterTitleDisplay()
        }

        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnRew1m.setOnClickListener { seekRelative(-60000L) }
        binding.btnRew10s.setOnClickListener { seekRelative(-10000L) }
        binding.btnFwd10s.setOnClickListener { seekRelative(10000L) }
        binding.btnFwd1m.setOnClickListener { seekRelative(60000L) }

        binding.btnLock.setOnClickListener {
            isLocked = !isLocked
            binding.seekBar.isEnabled = !isLocked
            binding.seekBarBook.isEnabled = !isLocked
            binding.btnLock.setColorFilter(if (isLocked) Color.parseColor("#4DA6FF") else Color.parseColor("#CCCCCC"))
            Toast.makeText(this, if (isLocked) "Перемотка заблокирована" else "Перемотка разблокирована", Toast.LENGTH_SHORT).show()
        }

        // Нижний ползунок: точная перемотка внутри текущей главы
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isLocked) {
                    recordHistory("Перемотка по главе")
                    playerService?.exoPlayer?.seekTo(progress.toLong())
                    saveBookState()
                    updateProgressUI()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Верхний ползунок: грубая перемотка по всей книге (с переходом между главами)
        binding.seekBarBook.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isLocked) {
                    val book = currentBook ?: return
                    val targetBookMs = (book.totalDurationMs * progress) / 1000L
                    var acc = 0L
                    for (i in book.chapters.indices) {
                        val chapDur = book.chapters.get(i).durationMs
                        if (targetBookMs < acc + chapDur || i == book.chapters.size - 1) {
                            val offsetInChap = (targetBookMs - acc).coerceAtLeast(0L)
                            val wasPlaying = playerService?.exoPlayer?.isPlaying == true
                            if (i != currentChapterIndex) {
                                playChapter(i, offsetInChap, wasPlaying)
                            } else {
                                playerService?.exoPlayer?.seekTo(offsetInChap)
                                updateProgressUI()
                            }
                            break
                        }
                        acc += chapDur
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnSpeed.setOnClickListener { showSpeedDialog() }
        binding.btnSleep.setOnClickListener { showSleepTimerDialog() }
        binding.btnAudio.setOnClickListener { showAudioEqualizerDialog() }
        binding.btnChapters.setOnClickListener { showChaptersAndBookmarksDialog() }
        binding.btnCharacters.setOnClickListener { showCharactersDialog() }
        binding.btnHistory.setOnClickListener { showHistoryDialog() }
    }
    private fun syncWithRunningServiceOrLoadSaved() {
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val lastBookUri = prefs.getString("last_book_uri", null)?.let(Uri::parse) ?: return
        val folder = findBookFolder(lastBookUri) ?: return

        val isAlreadyPlaying = playerService?.exoPlayer?.isPlaying == true
        val stateKey = prefs.getString("last_book_state_key", lastBookUri.toString()) ?: lastBookUri.toString()
        loadSingleBook(lastBookUri, stateKey, folder.name, autoPlay = false, keepRunningService = isAlreadyPlaying)
    }

    private fun findBookFolder(bookUri: Uri): DocumentFile? {
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val treeUri = prefs.getString("root_tree_uri", null)?.let(Uri::parse) ?: return null
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return null
        if (root.uri == bookUri) return root
        return root.listFiles().firstOrNull { it.isDirectory && it.uri == bookUri }
    }

    private fun loadSingleBook(folderUri: Uri, stateKey: String, bookTitle: String?, autoPlay: Boolean, keepRunningService: Boolean = false) {
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val folder = findBookFolder(folderUri) ?: return
        val audioFiles = folder.listFiles()
            .filter { it.isFile && audioExtensions.contains(it.name.orEmpty().substringAfterLast('.', "").lowercase()) }
            .sortedWith(compareBy({ it.name.orEmpty().length }, { it.name.orEmpty().lowercase() }))

        if (audioFiles.isEmpty()) return

        val retriever = MediaMetadataRetriever()
        val chapters = audioFiles.map { file ->
            var dur = 0L
            var titleTag: String? = null
            try {
                retriever.setDataSource(this, file.uri)
                dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                titleTag = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            } catch (_: Exception) {}
            ChapterItem(file.uri, file.name ?: "Глава", dur, titleTag)
        }
        try { retriever.release() } catch (_: Exception) {}

        val totalDur = chapters.sumOf { it.durationMs }
        val cover = folder.listFiles()?.firstOrNull {
            it.isFile && it.name.orEmpty().substringAfterLast('.', "").lowercase() in setOf("jpg", "png")
        }

        val book = BookItem(folderUri, stateKey, bookTitle ?: folder.name ?: "Книга", chapters, totalDur, cover?.uri)
        currentBook = book
        prefs.edit()
            .putString("last_book_uri", folderUri.toString())
            .putString("last_book_state_key", stateKey)
            .apply()

        binding.tvBookTitle.text = book.title
        if (book.coverUri != null) {
            binding.ivCover.setImageBitmap(try {
                contentResolver.openInputStream(book.coverUri)?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null })
        } else {
            binding.ivCover.setImageDrawable(null)
        }

        val savedChapter = prefs.getInt("chap_$stateKey", 0).coerceIn(0, chapters.size - 1)
        val savedPos = prefs.getLong("pos_ms_$stateKey", 0L)
        currentChapterIndex = savedChapter

        if (keepRunningService) {
            refreshChapterTitleDisplay()
            updateProgressUI()
        } else {
            playChapter(savedChapter, savedPos, autoPlay)
        }
    }

    private fun formatChapterDisplayTitle(index: Int, chapter: ChapterItem): String {
        val raw = chapter.rawTitle?.trim()
        val title = when (id3Mode) {
            1 -> if (!raw.isNullOrEmpty()) raw else chapter.name.substringBeforeLast('.', chapter.name)
            2 -> if (!raw.isNullOrEmpty()) {
                try {
                    String(raw.toByteArray(Charsets.ISO_8859_1), Charset.forName("windows-1251"))
                } catch (_: Exception) { raw }
            } else chapter.name.substringBeforeLast('.', chapter.name)
            else -> chapter.name.substringBeforeLast('.', chapter.name)
        }
        val total = currentBook?.chapters?.size ?: 1
        return "${index + 1}/$total: $title"
    }

    private fun refreshChapterTitleDisplay() {
        val book = currentBook ?: return
        if (currentChapterIndex in book.chapters.indices) {
            val text = formatChapterDisplayTitle(currentChapterIndex, book.chapters.get(currentChapterIndex))
            binding.tvChapterTitle.text = text
            playerService?.let {
                it.currentChapterTitle = text
                it.updateMediaSessionAndNotification()
            }
        }
    }

    private fun playChapter(index: Int, startPosMs: Long, autoPlay: Boolean) {
        val book = currentBook ?: return
        if (index !in book.chapters.indices) return
        currentChapterIndex = index
        val chapter = book.chapters.get(index)
        val chapTitle = formatChapterDisplayTitle(index, chapter)

        binding.tvChapterTitle.text = chapTitle
        binding.seekBar.max = chapter.durationMs.toInt().coerceAtLeast(100)
        binding.seekBar.progress = startPosMs.toInt()
        binding.tvChapterDuration.text = formatTime(chapter.durationMs)

        playerService?.playFile(
            uri = chapter.uri,
            bookTitle = book.title,
            chapterTitle = chapTitle,
            coverUri = book.coverUri,
            startPosMs = startPosMs,
            speed = playbackSpeed,
            autoPlay = autoPlay
        )

        if (autoPlay) {
            getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
                .edit().putString("status_${book.stateKey}", "started").apply()
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            handler.post(updateRunnable)
        } else {
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }
        updateProgressUI()
    }

    private fun togglePlayPause() {
        val player = playerService?.exoPlayer ?: return
        val book = currentBook ?: return
        if (player.isPlaying) {
            player.pause()
            saveBookState()
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            handler.removeCallbacks(updateRunnable)
        } else {
            val autoRewind = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE).getInt("auto_rewind", 10)
            if (autoRewind > 0 && player.currentPosition > autoRewind * 1000L) {
                player.seekTo(player.currentPosition - autoRewind * 1000L)
            }
            getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
                .edit().putString("status_${book.stateKey}", "started").apply()
            player.play()
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            handler.post(updateRunnable)
        }
        playerService?.updateMediaSessionAndNotification()
    }

    private fun seekRelative(deltaMs: Long) {
        if (isLocked) {
            Toast.makeText(this, "Сначала разблокируйте замок сверху", Toast.LENGTH_SHORT).show()
            return
        }
        val player = playerService?.exoPlayer ?: return
        val dur = player.duration
        if (dur <= 0L) return
        recordHistory(if (deltaMs < 0) "Назад" else "Вперед")
        val target = (player.currentPosition + deltaMs).coerceIn(0L, dur - 200L)
        player.seekTo(target)
        saveBookState()
        updateProgressUI()
        playerService?.updateMediaSessionAndNotification()
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (playerService?.exoPlayer?.isPlaying == true) {
                updateProgressUI()
                saveBookState()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun updateProgressUI() {
        val book = currentBook ?: return
        val player = playerService?.exoPlayer ?: return
        val curPos = player.currentPosition.coerceAtLeast(0L)
        val chapDur = player.duration.coerceAtLeast(1L)

        binding.seekBar.max = chapDur.toInt()
        binding.seekBar.progress = curPos.toInt()
        binding.tvChapterElapsed.text = formatTime(curPos)
        binding.tvChapterDuration.text = formatTime(chapDur)

        var readMs = 0L
        for (i in 0 until currentChapterIndex) {
            readMs += book.chapters.get(i).durationMs
        }
        readMs += curPos
        val totalMs = book.totalDurationMs.coerceAtLeast(1L)
        val leftMs = (totalMs - readMs).coerceAtLeast(0L)
        val percent = ((readMs * 100) / totalMs).toInt().coerceIn(0, 100)

        binding.seekBarBook.progress = ((readMs * 1000L) / totalMs).toInt().coerceIn(0, 1000)
        binding.tvTotalProgress.text = getString(
            R.string.progress_format,
            formatTime(readMs),
            formatTime(totalMs),
            percent,
            formatTime(leftMs)
        )
    }

    private fun saveBookState() {
        val book = currentBook ?: return
        val pos = playerService?.exoPlayer?.currentPosition ?: return
        getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE).edit()
            .putInt("chap_${book.stateKey}", currentChapterIndex)
            .putLong("pos_ms_${book.stateKey}", pos)
            .apply()
    }
    private fun applyAudioEffects() {
        val service = playerService ?: return
        val sessionId = service.exoPlayer?.audioSessionId ?: return
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            service.loudnessEnhancer?.release()
            service.loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                setTargetGain(boostGainMb)
                enabled = boostGainMb > 0
            }
        } catch (_: Exception) {}

        try {
            service.equalizer?.release()
            service.equalizer = Equalizer(0, sessionId).apply {
                enabled = true
                val bands = numberOfBands.toInt()
                val minLevel = bandLevelRange.get(0)
                val maxLevel = bandLevelRange.get(1)
                for (i in 0 until bands) {
                    val level: Short = when (eqPresetIndex) {
                        1 -> if (i in 1..3) (maxLevel * 0.7).toInt().toShort() else (minLevel * 0.4).toInt().toShort()
                        2 -> if (i == 0) (maxLevel * 0.8).toInt().toShort() else 0
                        else -> 0
                    }
                    setBandLevel(i.toShort(), level)
                }
            }
        } catch (_: Exception) {}
    }

    private fun showAudioEqualizerDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val tvBoost = TextView(this).apply { text = "Усиление тихой громкости (Boost): +${boostGainMb / 100} дБ" }
        val seekBoost = SeekBar(this).apply {
            max = 1500
            progress = boostGainMb
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    boostGainMb = p
                    tvBoost.text = "Усиление тихой громкости (Boost): +${boostGainMb / 100} дБ"
                    applyAudioEffects()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val tvEq = TextView(this).apply {
            text = "\nПрофиль эквалайзера:"
            setPadding(0, 16, 0, 8)
        }
        val presets = arrayOf("Обычный (ровный)", "Четкая речь (убрать гул)", "Глубокий бас")
        val radioGroup = RadioGroup(this).apply {
            presets.forEachIndexed { idx, title ->
                val rb = RadioButton(this@MainActivity).apply {
                    text = title
                    id = idx
                }
                addView(rb)
            }
            check(eqPresetIndex)
            setOnCheckedChangeListener { _, checkedId ->
                eqPresetIndex = checkedId
                applyAudioEffects()
            }
        }
        layout.addView(tvBoost)
        layout.addView(seekBoost)
        layout.addView(tvEq)
        layout.addView(radioGroup)

        AlertDialog.Builder(this)
            .setTitle("Звук и эквалайзер")
            .setView(layout)
            .setPositiveButton("Готово", null)
            .show()
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.8x", "0.9x", "1.0x", "1.1x", "1.2x", "1.3x", "1.5x", "1.7x", "2.0x")
        val vals = floatArrayOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.5f, 1.7f, 2.0f)
        AlertDialog.Builder(this)
            .setTitle("Скорость воспроизведения")
            .setItems(speeds) { _, which ->
                playbackSpeed = vals.get(which)
                binding.btnSpeed.text = speeds.get(which)
                getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE).edit().putFloat("playback_speed", playbackSpeed).apply()
                playerService?.exoPlayer?.playbackParameters = PlaybackParameters(playbackSpeed)
            }
            .show()
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("Выключить таймер", "15 минут", "30 минут", "45 минут", "60 минут")
        val mins = intArrayOf(0, 15, 30, 45, 60)
        AlertDialog.Builder(this)
            .setTitle("Таймер сна (продлевается встряхиванием)")
            .setItems(options) { _, which ->
                sleepTimer?.cancel()
                if (mins.get(which) == 0) {
                    sleepRemainingMs = 0L
                    binding.btnSleep.setColorFilter(Color.parseColor("#CCCCCC"))
                    sensorManager.unregisterListener(this)
                } else {
                    lastSleepDurationMs = mins.get(which) * 60_000L
                    startSleepCountdown(lastSleepDurationMs)
                }
            }
            .show()
    }

    private fun startSleepCountdown(durationMs: Long) {
        sleepTimer?.cancel()
        binding.btnSleep.setColorFilter(Color.parseColor("#4DA6FF"))
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sleepTimer = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) { sleepRemainingMs = millisUntilFinished }
            override fun onFinish() {
                if (playerService?.exoPlayer?.isPlaying == true) togglePlayPause()
                binding.btnSleep.setColorFilter(Color.parseColor("#CCCCCC"))
                sensorManager.unregisterListener(this@MainActivity)
            }
        }.start()
        Toast.makeText(this, "Таймер сна: ${durationMs / 60000} мин", Toast.LENGTH_SHORT).show()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || sleepRemainingMs <= 0) return
        val x = event.values.get(0)
        val y = event.values.get(1)
        val z = event.values.get(2)
        val gForce = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
        if (gForce > 2.2 && System.currentTimeMillis() - lastShakeTime > 2000) {
            lastShakeTime = System.currentTimeMillis()
            startSleepCountdown(lastSleepDurationMs)
            Toast.makeText(this, "Таймер сна продлен встряхиванием!", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun showChaptersAndBookmarksDialog() {
        val book = currentBook ?: return
        val items = book.chapters.mapIndexed { idx, ch ->
            val mark = if (idx == currentChapterIndex) "▶ " else ""
            "$mark${formatChapterDisplayTitle(idx, ch)} (${formatTime(ch.durationMs)})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Главы книги (${book.chapters.size})")
            .setItems(items) { _, which -> playChapter(which, 0L, true) }
            .setNeutralButton("Закладки") { _, _ -> showBookmarksDialog() }
            .show()
    }

    private fun showBookmarksDialog() {
        val book = currentBook ?: return
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val key = "bookmarks_${book.stateKey}"
        val raw = prefs.getString(key, "") ?: ""
        val list = if (raw.isEmpty()) mutableListOf() else raw.split(";;").toMutableList()

        val display = list.map {
            val parts = it.split("|")
            "Глава ${parts.get(0).toInt() + 1} (${formatTime(parts.get(1).toLong())}) — ${parts.getOrElse(2) { "" }}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Закладки")
            .setItems(display) { _, which ->
                val parts = list.get(which).split("|")
                playChapter(parts.get(0).toInt(), parts.get(1).toLong(), true)
            }
            .setPositiveButton("+ Добавить закладку") { _, _ ->
                val input = EditText(this).apply { hint = "Заметка (необязательно)" }
                AlertDialog.Builder(this)
                    .setTitle("Новая закладка")
                    .setView(input)
                    .setPositiveButton("Сохранить") { _, _ ->
                        val pos = playerService?.exoPlayer?.currentPosition ?: 0L
                        list.add("$currentChapterIndex|$pos|${input.text}")
                        prefs.edit().putString(key, list.joinToString(";;")).apply()
                        Toast.makeText(this, "Закладка сохранена", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .show()
    }

    private fun showCharactersDialog() {
        val book = currentBook ?: return
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val key = "chars_${book.stateKey}"
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            hint = "Записывайте сюда имена героев и заметки..."
            setText(prefs.getString(key, ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Персонажи: ${book.title}")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit().putString(key, input.text.toString()).apply()
            }
            .show()
    }

    private fun recordHistory(action: String) {
        val pos = playerService?.exoPlayer?.currentPosition ?: return
        positionHistory.add(0, "$action: Глава ${currentChapterIndex + 1} — ${formatTime(pos)}")
        if (positionHistory.size > 20) positionHistory.removeLast()
    }

    private fun showHistoryDialog() {
        if (positionHistory.isEmpty()) {
            Toast.makeText(this, "История переходов пока пуста", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("История позиций")
            .setItems(positionHistory.toTypedArray(), null)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun applyToolbarVisibility() {
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val views = listOf(binding.btnSleep, binding.btnAudio, binding.btnChapters, binding.btnSpeed, binding.btnCharacters, binding.btnHistory, binding.btnLock)
        for (i in buttonKeys.indices) {
            views.get(i).visibility = if (prefs.getBoolean(buttonKeys.get(i), true)) View.VISIBLE else View.GONE
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveBookState()
        handler.removeCallbacks(updateRunnable)
        sensorManager.unregisterListener(this)
        if (isBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
        }
    }
}                    