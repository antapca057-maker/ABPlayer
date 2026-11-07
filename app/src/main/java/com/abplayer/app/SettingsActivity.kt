package com.abplayer.app

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.abplayer.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val id3ModeNames = arrayOf(
        "По именам файлов (без тегов)",
        "Из тегов ID3 (UTF-8)",
        "Из тегов ID3 (Windows-1251)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)

        binding.btnBackSettings.setOnClickListener { finish() }

        // Автоперемотка после паузы
        fun updateAutoRewindText() {
            val sec = prefs.getInt("auto_rewind", 10)
            binding.tvAutoRewindDesc.text = if (sec == 0) "Выключено" else "Откат на $sec сек при возобновлении"
        }
        updateAutoRewindText()

        binding.rowAutoRewind.setOnClickListener {
            val values = arrayOf("Выключено (0 сек)", "5 секунд", "10 секунд", "20 секунд", "30 секунд")
            val secs = intArrayOf(0, 5, 10, 20, 30)
            AlertDialog.Builder(this)
                .setTitle("Автоперемотка после паузы")
                .setItems(values) { _, which ->
                    prefs.edit().putInt("auto_rewind", secs.get(which)).apply()
                    updateAutoRewindText()
                }
                .show()
        }

        // Режим ID3 по умолчанию
        fun updateId3Text() {
            val mode = prefs.getInt("id3_mode", 0).coerceIn(0, 2)
            binding.tvId3Desc.text = id3ModeNames.get(mode)
        }
        updateId3Text()

        binding.rowId3Default.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Режим названий глав")
                .setItems(id3ModeNames) { _, which ->
                    prefs.edit().putInt("id3_mode", which).apply()
                    updateId3Text()
                }
                .show()
        }

        // Чекбоксы видимости кнопок верхней панели
        val checkBoxes = listOf(
            binding.cbShowSleep to "show_sleep",
            binding.cbShowAudio to "show_audio",
            binding.cbShowChapters to "show_chapters",
            binding.cbShowSpeed to "show_speed",
            binding.cbShowCharacters to "show_characters",
            binding.cbShowHistory to "show_history",
            binding.cbShowLock to "show_lock"
        )

        for ((cb, key) in checkBoxes) {
            cb.isChecked = prefs.getBoolean(key, true)
            cb.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(key, isChecked).apply()
            }
        }
    }
}
