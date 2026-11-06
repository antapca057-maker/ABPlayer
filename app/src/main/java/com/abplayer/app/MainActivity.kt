package com.abplayer.app

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.abplayer.app.databinding.ActivityMainBinding
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var mediaPlayer: MediaPlayer? = null
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

    private val audioExtensions = setOf("mp3", "m4b", "m4a", "ogg", "flac", "wav", "aac", "opus")
    private val buttonKeys = arrayOf("show_sleep", "show_audio", "show_chapters", "show_speed", "show_characters", "show_history", "show_lock")
    private val buttonNames = arrayOf("Таймер сна", "Звук и эквалайзер", "Главы и закладки", "Скорость (1.0x)", "Персонажи", "История позиций", "Замок блокировки")

    data class ChapterItem(val file: File, val durationMs: Long)
    data class BookItem(val folder: File, val title: String, val chapters: List<ChapterItem>, val totalDurationMs: Long, val coverFile: File?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        autoRewindSec = prefs.getInt("auto_rewind", 10)
        playbackSpeed = prefs.getFloat("playback_speed", 1.0f)
        binding.btnSpeed.text = String.format(Locale.US, "%.1fx", playbackSpeed)

        applyToolbarVisibility()
        setupListeners()
        checkStoragePermissionAndLoad()
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
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadLibraryFromSavedRoot()
        } else {
            Toast.makeText(this, "Нужно разрешение на чтение памяти для поиска книг", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupListeners() {
        binding.btnSettings.setOnClickListener { showMainSettingsMenu() }
        binding.btnLibrary.setOnClickListener { showLibraryDialog() }
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }

        binding.btnRew1m.setOnClickListener { seekRelative(-60000) }
        binding.btnRew10s.setOnClickListener { seekRelative(-10000) }
        binding.btnFwd10s.setOnClickListener { seekRelative(10000) }
        binding.btnFwd1m.setOnClickListener { seekRelative(60000) }

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
                    mediaPlayer?.seekTo(progress)
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

    // --- ГЛАВНОЕ МЕНЮ НАСТРОЕК ---
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
                autoRewindSec = secs[which]
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
                } else if (items[which].startsWith("⬆")) {
                    currentDir.parentFile?.let { showFolderPickerDialog(it) }
                } else {
                    val dirName = items[which].removePrefix("📁 ")
                    showFolderPickerDialog(File(currentDir, dirName))
                }
            }
            .show()
    }

    // --- СКАНЕР ПАПОК И БИБЛИОТЕКА ---
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
