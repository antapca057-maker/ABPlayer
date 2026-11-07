package com.abplayer.app

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.abplayer.app.databinding.ActivityMainBinding
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var isLocked = false
    private var playbackSpeed = 1.0f
    private var boostGainMb = 0
    private var eqPresetIndex = 0
    private var autoRewindSec = 10

    private var booksList = mutableListOf<BookItem>()
    private var currentBook: BookItem? = null
    private var currentChapterIndex = 0
    private var positionHistory = mutableListOf<String>()

    private val handler = Handler(Looper.getMainLooper())
    private var sleepTimer: CountDownTimer? = null
    private var sleepRemainingMs = 0L
    private var lastSleepDurationMs = 0L
    private lateinit var sensorManager: SensorManager
    private var lastShakeTime = 0L

    private var playerService: PlayerService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlayerService.LocalBinder
            playerService = binder.getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playerService = null
        }
    }

    private val audioExtensions = setOf("mp3", "m4b", "m4a", "ogg", "flac", "wav", "aac", "opus")
    private val buttonKeys = arrayOf("show_sleep", "show_audio", "show_chapters", "show_speed", "show_characters", "show_history", "show_lock")
    private val buttonNames = arrayOf("Таймер сна", "Звук и эквалайзер", "Главы и закладки", "Скорость (1.0x)", "Персонажи", "История позиций", "Замок блокировки")

    data class ChapterItem(val file: File, val durationMs: Long)
    data class BookItem(val folder: File, val title: String, val chapters: List<ChapterItem>, val totalDurationMs: Long, val coverFile: File?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val serviceIntent = Intent(this, PlayerService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        autoRewindSec = prefs.getInt("auto_rewind", 10)
        playbackSpeed = prefs.getFloat("playback_speed", 1.0f)
        binding.btnSpeed.text = String.format(Locale.US, "%.1fx", playbackSpeed)

        initExoPlayer()
        applyToolbarVisibility()
        setupListeners()
        checkStoragePermissionAndLoad()
    }

    private fun initExoPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            binding.seekBar.max = duration.toInt().coerceAtLeast(100)
                            applyAudioEffects()
                            updateProgressUI()
                        } else if (state == Player.STATE_ENDED) {
                            val book = currentBook ?: return
                            if (currentChapterIndex + 1 < book.chapters.size) {
                                playChapter(currentChapterIndex + 1, 0L, true)
                            } else {
                                getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
                                    .edit().putString("status_${book.folder.absolutePath}", "finished").apply()
                                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                                playerService?.stopForegroundPlayer()
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(this@MainActivity, "Ошибка воспроизведения: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                })
            }
    }

    private fun checkStoragePermissionAndLoad() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 101)
        } else {
            loadLibraryFromSavedRoot()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults.get(0) == PackageManager.PERMISSION_GRANTED) {
            loadLibraryFromSavedRoot()
        } else {
            Toast.makeText(this, "Нужно разрешение на чтение памяти для поиска книг", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupListeners() {
        binding.btnSettings.setOnClickListener { showMainSettingsMenu() }
        binding.btnLibrary.setOnClickListener { showLibraryDialog() }
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }

        binding.btnRew1m.setOnClickListener { seekRelative(-60000L) }
        binding.btnRew10s.setOnClickListener { seekRelative(-10000L) }
        binding.btnFwd10s.setOnClickListener { seekRelative(10000L) }
        binding.btnFwd1m.setOnClickListener { seekRelative(60000L) }

        binding.btnLock.setOnClickListener {
            isLocked = !isLocked
            binding.seekBar.isEnabled = !isLocked
            binding.btnLock.setColorFilter(if (isLocked) Color.parseColor("#4DA6FF") else Color.parseColor("#CCCCCC"))
            Toast.makeText(this, if (isLocked) "Перемотка заблокирована" else "Перемотка разблокирована", Toast.LENGTH_SHORT).show()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isLocked) {
                    recordHistory("Перемотка")
                    exoPlayer?.seekTo(progress.toLong())
                    saveBookState()
                    updateProgressUI()
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
    private fun showMainSettingsMenu() {
        val options = arrayOf(
            "📁 Выбрать корневую папку с книгами",
            "👁 Настройка отображаемых кнопок",
            "⏪ Автоперемотка назад после паузы ($autoRewindSec сек)"
        )
        AlertDialog.Builder(this)
            .setTitle("Настройки ABPlayer")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showFolderPickerDialog(Environment.getExternalStorageDirectory())
                    1 -> showToolbarSettingsDialog()
                    2 -> showAutoRewindDialog()
                }
            }
            .show()
    }

    private fun showAutoRewindDialog() {
        val values = arrayOf("Выключено (0 сек)", "5 секунд", "10 секунд", "20 секунд", "30 секунд")
        val secs = intArrayOf(0, 5, 10, 20, 30)
        AlertDialog.Builder(this)
            .setTitle("Автоперемотка после паузы")
            .setItems(values) { _, which ->
                autoRewindSec = secs.get(which)
                getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE).edit().putInt("auto_rewind", autoRewindSec).apply()
            }
            .show()
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
                    getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE).edit().putString("root_folder", currentDir.absolutePath).apply()
                    loadLibraryFromSavedRoot()
                } else if (items.get(which).startsWith("⬆")) {
                    currentDir.parentFile?.let { showFolderPickerDialog(it) }
                } else {
                    val dirName = items.get(which).removePrefix("📁 ")
                    showFolderPickerDialog(File(currentDir, dirName))
                }
            }
            .show()
    }

    private fun loadLibraryFromSavedRoot() {
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val rootPath = prefs.getString("root_folder", null) ?: return
        val rootDir = File(rootPath)
        if (!rootDir.exists() || !rootDir.isDirectory) return

        booksList.clear()
        val foldersToScan = mutableListOf<File>()
        foldersToScan.add(rootDir)
        rootDir.listFiles()?.filter { it.isDirectory }?.forEach { foldersToScan.add(it) }

        val retriever = MediaMetadataRetriever()
        for (folder in foldersToScan) {
            val audioFiles = folder.listFiles()
                ?.filter { it.isFile && audioExtensions.contains(it.extension.lowercase()) }
                ?.sortedWith(compareBy({ it.name.length }, { it.name.lowercase() })) ?: continue

            if (audioFiles.isNotEmpty()) {
                val chapters = audioFiles.map { file ->
                    val dur = try {
                        retriever.setDataSource(file.absolutePath)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } catch (e: Exception) { 0L }
                    ChapterItem(file, dur)
                }
                val totalDur = chapters.sumOf { it.durationMs }
                val cover = folder.listFiles()?.firstOrNull {
                    it.isFile && (it.extension.lowercase() == "jpg" || it.extension.lowercase() == "png")
                }
                booksList.add(BookItem(folder, folder.name, chapters, totalDur, cover))
            }
        }
        try { retriever.release() } catch (_: Exception) {}

        val lastBookPath = prefs.getString("last_book_path", null)
        val bookToOpen = booksList.firstOrNull { it.folder.absolutePath == lastBookPath } ?: booksList.firstOrNull()
        if (bookToOpen != null) {
            openBook(bookToOpen, autoPlay = false)
        } else {
            Toast.makeText(this, "В выбранной папке аудиофайлы не найдены", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLibraryDialog() {
        if (booksList.isEmpty()) {
            Toast.makeText(this, "Сначала выберите папку с книгами в настройках", Toast.LENGTH_SHORT).show()
            showFolderPickerDialog(Environment.getExternalStorageDirectory())
            return
        }
        val categories = arrayOf("📚 Все книги (${booksList.size})", "🆕 Новые", "🎧 Начатые", "✅ Прочитанные")
        AlertDialog.Builder(this)
            .setTitle("Библиотека")
            .setItems(categories) { _, catIndex ->
                val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
                val filtered = booksList.filter { book ->
                    val status = prefs.getString("status_${book.folder.absolutePath}", "new")
                    when (catIndex) {
                        1 -> status == "new"
                        2 -> status == "started"
                        3 -> status == "finished"
                        else -> true
                    }
                }
                if (filtered.isEmpty()) {
                    Toast.makeText(this, "В этой вкладке пусто", Toast.LENGTH_SHORT).show()
                } else {
                    val titles = filtered.map { "${it.title} (${it.chapters.size} гл.)" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle(categories.get(catIndex))
                        .setItems(titles) { _, idx -> openBook(filtered.get(idx), autoPlay = true) }
                        .show()
                }
            }
            .show()
    }
    private fun openBook(book: BookItem, autoPlay: Boolean) {
        currentBook = book
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_book_path", book.folder.absolutePath).apply()

        binding.tvBookTitle.text = book.title
        if (book.coverFile != null) {
            binding.ivCover.setImageBitmap(BitmapFactory.decodeFile(book.coverFile.absolutePath))
        } else {
            binding.ivCover.setImageDrawable(null)
        }

        val savedChapter = prefs.getInt("chap_${book.folder.absolutePath}", 0).coerceIn(0, book.chapters.size - 1)
        val savedPos = prefs.getLong("pos_ms_${book.folder.absolutePath}", 0L)
        playChapter(savedChapter, savedPos, autoPlay)
    }

    private fun playChapter(index: Int, startPosMs: Long, autoPlay: Boolean) {
        val book = currentBook ?: return
        if (index !in book.chapters.indices) return
        currentChapterIndex = index
        val chapter = book.chapters.get(index)

        val player = exoPlayer ?: return
        val mediaItem = MediaItem.fromUri(Uri.fromFile(chapter.file))
        player.setMediaItem(mediaItem)
        player.prepare()
        if (startPosMs > 0L) {
            player.seekTo(startPosMs)
        }
        player.playbackParameters = PlaybackParameters(playbackSpeed)

        val chapText = "${index + 1}/${book.chapters.size}: ${chapter.file.nameWithoutExtension}"
        binding.tvChapterTitle.text = chapText
        binding.seekBar.progress = startPosMs.toInt()

        if (autoPlay) {
            startPlayback()
        } else {
            player.pause()
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }
        updateProgressUI()
    }

    private fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            saveBookState()
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            handler.removeCallbacks(updateRunnable)
            playerService?.stopForegroundPlayer()
        } else {
            if (autoRewindSec > 0 && player.currentPosition > autoRewindSec * 1000L) {
                player.seekTo(player.currentPosition - autoRewindSec * 1000L)
            }
            startPlayback()
        }
    }

    private fun startPlayback() {
        val book = currentBook ?: return
        getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
            .edit().putString("status_${book.folder.absolutePath}", "started").apply()
        exoPlayer?.play()
        binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        playerService?.startForegroundPlayer(book.title, binding.tvChapterTitle.text.toString())
        handler.post(updateRunnable)
    }

    private fun seekRelative(deltaMs: Long) {
        if (isLocked) {
            Toast.makeText(this, "Сначала разблокируйте замок сверху", Toast.LENGTH_SHORT).show()
            return
        }
        val player = exoPlayer ?: return
        val dur = player.duration
        if (dur <= 0L) return
        recordHistory(if (deltaMs < 0) "Назад" else "Вперед")
        val target = (player.currentPosition + deltaMs).coerceIn(0L, dur - 200L)
        player.seekTo(target)
        binding.seekBar.progress = target.toInt()
        saveBookState()
        updateProgressUI()
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (exoPlayer?.isPlaying == true) {
                updateProgressUI()
                saveBookState()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun updateProgressUI() {
        val book = currentBook ?: return
        val player = exoPlayer ?: return
        val curPos = player.currentPosition.coerceAtLeast(0L)
        binding.seekBar.progress = curPos.toInt()

        var readMs = 0L
        for (i in 0 until currentChapterIndex) {
            readMs += book.chapters.get(i).durationMs
        }
        readMs += curPos
        val totalMs = book.totalDurationMs.coerceAtLeast(1L)
        val leftMs = (totalMs - readMs).coerceAtLeast(0L)
        val percent = ((readMs * 100) / totalMs).toInt().coerceIn(0, 100)

        binding.tvTotalProgress.text = "Read ${formatTime(readMs)} of ${formatTime(totalMs)}   $percent%   Left ${formatTime(leftMs)}"
    }

    private fun saveBookState() {
        val book = currentBook ?: return
        val pos = exoPlayer?.currentPosition ?: return
        getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE).edit()
            .putInt("chap_${book.folder.absolutePath}", currentChapterIndex)
            .putLong("pos_ms_${book.folder.absolutePath}", pos)
            .apply()
    }
    private fun applyAudioEffects() {
        val sessionId = exoPlayer?.audioSessionId ?: return
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                setTargetGain(boostGainMb)
                enabled = boostGainMb > 0
            }
        } catch (_: Exception) {}

        try {
            equalizer?.release()
            equalizer = Equalizer(0, sessionId).apply {
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
        val presets = arrayOf("Обычный (ровный)", "🗣 Четкая речь (убрать гул)", "🔊 Глубокий бас")
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
                exoPlayer?.playbackParameters = PlaybackParameters(playbackSpeed)
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
                if (exoPlayer?.isPlaying == true) togglePlayPause()
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
            "$mark${idx + 1}. ${ch.file.nameWithoutExtension} (${formatTime(ch.durationMs)})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Главы книги (${book.chapters.size})")
            .setItems(items) { _, which -> playChapter(which, 0L, true) }
            .setNeutralButton("🔖 Закладки") { _, _ -> showBookmarksDialog() }
            .show()
    }

    private fun showBookmarksDialog() {
        val book = currentBook ?: return
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val key = "bookmarks_${book.folder.absolutePath}"
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
                        val pos = exoPlayer?.currentPosition ?: 0L
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
        val key = "chars_${book.folder.absolutePath}"
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
        val pos = exoPlayer?.currentPosition ?: return
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

    private fun showToolbarSettingsDialog() {
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val checkedItems = BooleanArray(buttonKeys.size) { i -> prefs.getBoolean(buttonKeys.get(i), true) }
        AlertDialog.Builder(this)
            .setTitle("Отображаемые кнопки")
            .setMultiChoiceItems(buttonNames, checkedItems) { _, which, isChecked ->
                prefs.edit().putBoolean(buttonKeys.get(which), isChecked).apply()
            }
            .setPositiveButton("Готово") { _, _ -> applyToolbarVisibility() }
            .show()
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
        exoPlayer?.release()
        equalizer?.release()
        loudnessEnhancer?.release()
        try { unbindService(serviceConnection) } catch (_: Exception) {}
    }
}            