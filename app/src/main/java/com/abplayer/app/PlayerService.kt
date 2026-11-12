package com.abplayer.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MediaStyle
import androidx.core.alarm.AlarmManagerCompat
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes as ExoAudioAttributes


class PlayerService : Service() {


    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }


    private val binder = LocalBinder()
    var exoPlayer: ExoPlayer? = null
    var equalizer: Equalizer? = null
    var loudnessEnhancer: LoudnessEnhancer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var headsetClickCount = 0
    private var lastHeadsetClickTime = 0L

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }


    var currentBookTitle: String = "ABPlayer"
    var currentChapterTitle: String = ""
    var currentCoverUri: Uri? = null
    var onChapterEndedCallback: (() -> Unit)? = null
    var onStateChangedCallback: (() -> Unit)? = null


    companion object {
        private const val CHANNEL_ID = "abplayer_channel"
        private const val NOTIF_ID = 101
        const val ACTION_PLAY_PAUSE = "com.abplayer.ACTION_PLAY_PAUSE"
        const val ACTION_REW = "com.abplayer.ACTION_REW"
        const val ACTION_FWD = "com.abplayer.ACTION_FWD"
        const val ACTION_STOP = "com.abplayer.ACTION_STOP"
    }


    override fun onCreate() {
        super.onCreate()

        // Создание notification-канала с аудиатрибутами
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val channel = NotificationChannel(CHANNEL_ID, "Воспроизведение", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false); setAudioAttributes(audioAttributes) }
        notificationManager.createNotificationChannel(channel)


        mediaSession = MediaSessionCompat(this, "ABPlayerSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    exoPlayer?.play()
                    updateMediaSessionAndNotification()
                }
                override fun onPause() {
                    exoPlayer?.pause()
                    updateMediaSessionAndNotification()
                }
                override fun onRewind() {
                    seekBy(-10000L)
                }
                override fun onFastForward() {
                    seekBy(10000L)
                }
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN &&
                        (keyEvent.keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    ) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastHeadsetClickTime < 500L) {
                            headsetClickCount++
                        } else {
                            headsetClickCount = 1
                        }
                        lastHeadsetClickTime = now

                        when (headsetClickCount) {
                            1 -> exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
                            2 -> {
                                exoPlayer?.play()
                                seekBy(10000L)
                            }
                            3 -> {
                                exoPlayer?.play()
                                seekBy(-10000L)
                            }
                        }
                        updateMediaSessionAndNotification()
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            isActive = true
        }


        val exoAudioAttributes = ExoAudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(exoAudioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateMediaSessionAndNotification()
                        onStateChangedCallback?.invoke()
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            onChapterEndedCallback?.invoke()
                        } else if (state == Player.STATE_READY) {
                            updateMediaSessionAndNotification()
                            onStateChangedCallback?.invoke()
                        }
                    }
                })
            }
    }


    private fun seekBy(deltaMs: Long) {
        exoPlayer?.let {
            val dur = it.duration.coerceAtLeast(0L)
            if (dur > 0L) {
                it.seekTo((it.currentPosition + deltaMs).coerceIn(0L, dur - 200L))
                updateMediaSessionAndNotification()
                onStateChangedCallback?.invoke()
            }
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
                updateMediaSessionAndNotification()
            }
            ACTION_REW -> seekBy(-10000L)
            ACTION_FWD -> seekBy(10000L)
            ACTION_STOP -> {
                exoPlayer?.pause()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }


    override fun onBind(intent: Intent?): IBinder = binder


    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (exoPlayer?.isPlaying == true) {
            val restartIntent = Intent(this, javaClass)
            restartIntent.action = PlayerService.ACTION_PLAY_PAUSE
            val restartFlags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val restartPendingIntent = PendingIntent.getService(
                this, 0, restartIntent, restartFlags
            )
            AlarmManagerCompat.setExactAndAllowWhileIdle(
                getSystemService(AlarmManager::class.java),
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartPendingIntent
            )
        } else {
            stopForeground(true)
            stopSelf()
        }
    }


    fun playFile(uri: Uri, bookTitle: String, chapterTitle: String, coverUri: Uri?, startPosMs: Long, speed: Float, autoPlay: Boolean) {
        currentBookTitle = bookTitle
        currentChapterTitle = chapterTitle
        currentCoverUri = coverUri

        val player = exoPlayer ?: return
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        if (startPosMs > 0L) player.seekTo(startPosMs)
        player.playbackParameters = PlaybackParameters(speed)

        if (autoPlay) player.play() else player.pause()

        // Инициализация эквалайзера и усиления тихой речи
        if (equalizer == null) {
            val sessionId = player.audioSessionId
            if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                equalizer = Equalizer(0, sessionId).also { it.setEnabled(true) }
            }
        }
        if (loudnessEnhancer == null) {
            val sessionId = player.audioSessionId
            if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                loudnessEnhancer = LoudnessEnhancer(sessionId).apply { setTargetGain(2000) }
                loudnessEnhancer?.enabled = true
            }
        }

        updateMediaSessionAndNotification()
    }


    fun updateMediaSessionAndNotification() {
        val player = exoPlayer
        val isPlaying = player?.isPlaying == true
        val pos = player?.currentPosition ?: 0L
        val dur = player?.duration?.coerceAtLeast(0L) ?: 0L

        val coverBitmap = currentCoverUri?.let { uri ->
            try {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }
        }

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentChapterTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentBookTitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
        if (coverBitmap != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, coverBitmap)
        }
        mediaSession.setMetadata(metadataBuilder.build())

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_FAST_FORWARD
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                pos,
                1.0f
            )
        mediaSession.setPlaybackState(stateBuilder.build())

        val pendingFlags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), pendingFlags
        )
        val rewIntent = PendingIntent.getService(this, 1, Intent(this, PlayerService::class.java).setAction(ACTION_REW), pendingFlags)
        val playPauseIntent = PendingIntent.getService(this, 2, Intent(this, PlayerService::class.java).setAction(ACTION_PLAY_PAUSE), pendingFlags)
        val fwdIntent = PendingIntent.getService(this, 3, Intent(this, PlayerService::class.java).setAction(ACTION_FWD), pendingFlags)
        val stopIntent = PendingIntent.getService(this, 4, Intent(this, PlayerService::class.java).setAction(ACTION_STOP), pendingFlags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (isPlaying) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentTitle(currentBookTitle)
            .setContentText(currentChapterTitle)
            .setContentIntent(openIntent)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_rew, "-10с", rewIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Пауза" else "Играть",
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_ff, "+10с", fwdIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть", stopIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        if (coverBitmap != null) {
            builder.setLargeIcon(coverBitmap)
        }

        // Фикс для Android 12+: startForeground с type для mediaPlayback
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, builder.build(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, builder.build())
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
        equalizer?.release()
        loudnessEnhancer?.release()
        exoPlayer?.release()
    }
}