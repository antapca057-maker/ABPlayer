package com.abplayer.app

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.abplayer.app.databinding.ActivitySettingsBinding
import org.json.JSONObject
import java.io.File

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

        // Экспорт бэкапа в Download/ABPlayer_backup.json
        binding.rowExportBackup.setOnClickListener {
            try {
                val json = JSONObject()
                for ((k, v) in prefs.all) {
                    when (v) {
                        is Boolean -> json.put(k, JSONObject().put("t", "B").put("v", v))
                        is Int -> json.put(k, JSONObject().put("t", "I").put("v", v))
                        is Long -> json.put(k, JSONObject().put("t", "L").put("v", v))
                        is Float -> json.put(k, JSONObject().put("t", "F").put("v", v.toDouble()))
                        is String -> json.put(k, JSONObject().put("t", "S").put("v", v))
                    }
                }
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadDir.exists()) downloadDir.mkdirs()
                val backupFile = File(downloadDir, "ABPlayer_backup.json")
                backupFile.writeText(json.toString(2))
                Toast.makeText(this, "Сохранено в ${backupFile.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Импорт бэкапа из Download/ABPlayer_backup.json
        binding.rowImportBackup.setOnClickListener {
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val backupFile = File(downloadDir, "ABPlayer_backup.json")
                if (!backupFile.exists()) {
                    Toast.makeText(this, "Файл ABPlayer_backup.json в папке Download не найден", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val json = JSONObject(backupFile.readText())
                val editor = prefs.edit()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val obj = json.getJSONObject(k)
                    when (obj.getString("t")) {
                        "B" -> editor.putBoolean(k, obj.getBoolean("v"))
                        "I" -> editor.putInt(k, obj.getInt("v"))
                        "L" -> editor.putLong(k, obj.getLong("v"))
                        "F" -> editor.putFloat(k, obj.getDouble("v").toFloat())
                        "S" -> editor.putString(k, obj.getString("v"))
                    }
                }
                editor.apply()
                updateAutoRewindText()
                updateId3Text()
                for ((cb, key) in checkBoxes) cb.isChecked = prefs.getBoolean(key, true)
                Toast.makeText(this, "Прогресс и закладки успешно восстановлены!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
