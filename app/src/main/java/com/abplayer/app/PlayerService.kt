package com.abplayer.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

class PlayerService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    fun startForegroundPlayer(bookTitle: String, chapterTitle: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = Notification.Builder(this)
            .setContentTitle(bookTitle)
            .setContentText(chapterTitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    fun stopForegroundPlayer() {
        stopForeground(true)
    }
}
