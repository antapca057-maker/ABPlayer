package com.abplayer.app

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.abplayer.app.databinding.ActivitySettingsBinding
import org.json.JSONObject

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

        val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                try {
                    val json = JSONObject()
                    for ((key, value) in prefs.all) {
                        when (value) {
                            is Boolean -> json.put(key, JSONObject().put("t", "B").put("v", value))
                            is Int -> json.put(key, JSONObject().put("t", "I").put("v", value))
                            is Long -> json.put(key, JSONObject().put("t", "L").put("v", value))
                            is Float -> json.put(key, JSONObject().put("t", "F").put("v", value.toDouble()))
                            is String -> json.put(key, JSONObject().put("t", "S").put("v", value))
                        }
                    }
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json.toString(2)) }
                        ?: throw IllegalStateException("Не удалось открыть файл для записи")
                    Toast.makeText(this, "Резервная копия сохранена", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { JSONObject(it.readText()) }
                        ?: throw IllegalStateException("Не удалось открыть файл")
                    val editor = prefs.edit()
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val obj = json.getJSONObject(key)
                        when (obj.getString("t")) {
                            "B" -> editor.putBoolean(key, obj.getBoolean("v"))
                            "I" -> editor.putInt(key, obj.getInt("v"))
                            "L" -> editor.putLong(key, obj.getLong("v"))
                            "F" -> editor.putFloat(key, obj.getDouble("v").toFloat())
                            "S" -> editor.putString(key, obj.getString("v"))
                        }
                    }
                    editor.apply()
                    updateAutoRewindText()
                    updateId3Text()
                    for ((checkBox, key) in checkBoxes) checkBox.isChecked = prefs.getBoolean(key, true)
                    Toast.makeText(this, "Прогресс и закладки успешно восстановлены!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.rowExportBackup.setOnClickListener {
            exportBackupLauncher.launch("ABPlayer_backup.json")
        }

        binding.rowImportBackup.setOnClickListener {
            importBackupLauncher.launch(arrayOf("application/json", "text/json", "*/*"))
        }
    }
}
