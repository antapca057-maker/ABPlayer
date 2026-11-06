package com.abplayer.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.abplayer.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isLocked = false

    private val buttonKeys = arrayOf(
        "show_sleep", "show_audio", "show_chapters",
        "show_speed", "show_characters", "show_history", "show_lock"
    )
    private val buttonNames = arrayOf(
        "Таймер сна", "Звук и усиление", "Главы и закладки",
        "Скорость (1.0x)", "Персонажи", "История позиций", "Замок блокировки"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyToolbarVisibility()

        // Кнопка настроек видимости иконок
        binding.btnSettings.setOnClickListener {
            showToolbarSettingsDialog()
        }

        // Логика замка блокировки перемотки
        binding.btnLock.setOnClickListener {
            isLocked = !isLocked
            binding.seekBar.isEnabled = !isLocked
            binding.btnLock.setColorFilter(if (isLocked) Color.parseColor("#4DA6FF") else Color.parseColor("#CCCCCC"))
            Toast.makeText(this, if (isLocked) "Перемотка заблокирована" else "Перемотка разблокирована", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyToolbarVisibility() {
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val views = listOf(
            binding.btnSleep, binding.btnAudio, binding.btnChapters,
            binding.btnSpeed, binding.btnCharacters, binding.btnHistory, binding.btnLock
        )
        for (i in buttonKeys.indices) {
            val isVisible = prefs.getBoolean(buttonKeys[i], true)
            views[i].visibility = if (isVisible) View.VISIBLE else View.GONE
        }
    }

    private fun showToolbarSettingsDialog() {
        val prefs = getSharedPreferences("abplayer_prefs", Context.MODE_PRIVATE)
        val checkedItems = BooleanArray(buttonKeys.size) { i -> prefs.getBoolean(buttonKeys[i], true) }

        AlertDialog.Builder(this)
            .setTitle("Отображаемые кнопки")
            .setMultiChoiceItems(buttonNames, checkedItems) { _, which, isChecked ->
                prefs.edit().putBoolean(buttonKeys[which], isChecked).apply()
            }
            .setPositiveButton("Готово") { _, _ ->
                applyToolbarVisibility()
            }
            .show()
    }
}
