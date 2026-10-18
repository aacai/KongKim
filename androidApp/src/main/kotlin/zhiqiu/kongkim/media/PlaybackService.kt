package zhiqiu.kongkim.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import zhiqiu.kongkim.MainActivity

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var prefs: AppPreferences
    private var audioOnly = false
    private var videoTrackDisabled = false
    /** 播完后 stop() 会保留结尾位置，重新点播时必须先重建 media item 才能回到开头 */
    private var stoppedAtEnd = false

    /** 播放中每秒推一次通知，锁屏/通知栏的进度条才会走字 */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            val session = mediaSession
            if (session != null && player.isPlaying) {
                onUpdateNotification(session, /* startInForeground = */ false)
            }
            mainHandler.postDelayed(this, PROGRESS_UPDATE_MS)
        }
    }

    /** 锁屏/通知栏封面：应用图标，懒加载一次，之后每次刷新通知直接复用 */
    private val artworkData: ByteArray by lazy {
        val bitmap = packageManager.getApplicationIcon(packageName)
            .toBitmap(width = ARTWORK_SIZE_PX, height = ARTWORK_SIZE_PX, config = Bitmap.Config.ARGB_8888)
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // 练完整套就交还解码器和缓冲队列，别让几十 MB 挂在后台
            if (playbackState == Player.STATE_ENDED) {
                stoppedAtEnd = true
                player.stop()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startProgressUpdates() else stopProgressUpdates()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        ensureMediaNotificationChannel()
        // 自定义通知：±10 秒按钮 + 进度条 + 剩余时间，各家“岛”/焦点通知也是拾取这条通知
        setMediaNotificationProvider(PlaybackNotificationProvider(this))
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // 通知/锁屏上的 ±10 秒按钮依赖这两个 increment
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            // 素材是本地 asset，带宽不是瓶颈，缓冲取短：省内存，起播和 seek 也更快
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 8_000,
                        /* maxBufferMs = */ 20_000,
                        /* bufferForPlaybackMs = */ 500,
                        /* bufferForPlaybackAfterSeekMs = */ 1_000
                    )
                    .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                    .build()
            )
            .build()
        player.addListener(playerListener)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(SessionCallback())
            .build()
    }

    /**
     * 自建媒体通知渠道并把重要性提到 HIGH、锁屏可见。
     * Media3 的 DefaultMediaNotificationProvider 在渠道已存在时会直接跳过、不覆盖，
     * 所以这里先于它建好高优先级渠道，锁屏/各家“岛”的媒体卡片才会稳定出现，
     * 不被系统或厂商压成低优先级/静音。
     */
    private fun ensureMediaNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
            "跟练播放",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "金刚功口令跟练的媒体播放通知"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONFIG, ACTION_ALARM -> {
                val track = MediaTrack.fromId(
                    intent.getStringExtra(EXTRA_TRACK) ?: prefs.track.id
                )
                val audioOnly = intent.getBooleanExtra(
                    EXTRA_AUDIO_ONLY,
                    if (intent.action == ACTION_ALARM) true else prefs.audioOnly
                )
                val speed = intent.getFloatExtra(EXTRA_SPEED, prefs.speed)
                val play = intent.getBooleanExtra(EXTRA_PLAY, true)
                applyConfig(track, audioOnly, speed, play)
                if (intent.action != ACTION_ALARM) {
                    prefs.track = track
                    prefs.audioOnly = audioOnly
                    prefs.speed = speed
                }
            }

            // 小窗上的播放/暂停按钮
            ACTION_TOGGLE -> {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun applyConfig(
        track: MediaTrack,
        audioOnly: Boolean,
        speed: Float,
        play: Boolean
    ) {
        this.audioOnly = audioOnly
        val uri = "asset:///${track.assetFile(audioOnly)}"
        val current = player.currentMediaItem
        if (current?.localConfiguration?.uri?.toString() != uri) {
            // 同一版本在画面/音频之间切换时保留进度，换版本才回到开头；
            // 停在结尾时不要续播，否则一点重播就会原地结束
            val resumeAt = if (stoppedAtEnd) {
                C.TIME_UNSET
            } else if (current?.mediaId == track.id) {
                player.currentPosition
            } else {
                C.TIME_UNSET
            }
            stoppedAtEnd = false
            player.setMediaItem(mediaItemFor(track))
            player.prepare()
            if (resumeAt != C.TIME_UNSET && resumeAt > 0) {
                player.seekTo(resumeAt)
            }
        } else if (player.playbackState == Player.STATE_IDLE) {
            // 播完后 stop() 释放了解码器，重新点播要先 prepare；
            // 停在结尾时重建 media item，从头开始而不是原地重播
            if (stoppedAtEnd) {
                stoppedAtEnd = false
                player.setMediaItem(mediaItemFor(track))
            }
            player.prepare()
        }
        applyVideoTrack()
        player.setPlaybackSpeed(speed)
        if (play) {
            player.playWhenReady = true
            player.play()
        }
    }

    /** 带上标题/版本/封面，锁屏、通知栏和各家“岛”才有东西可显示 */
    private fun mediaItemFor(track: MediaTrack): MediaItem =
        MediaItem.fromUri("asset:///${track.assetFile(audioOnly)}")
            .buildUpon()
            .setMediaId(track.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(getString(zhiqiu.kongkim.R.string.app_name))
                    .setArtist("${track.label} · 口令跟练")
                    .setArtworkData(artworkData)
                    .build()
            )
            .build()

    /**
     * 只听声音时撤掉视频轨道，省电也省内存。
     * 息屏/切后台不用管：ExoPlayer 自己通过 SurfaceHolder 回调撤视频输出保声音，
     * 亮屏时 surfaceCreated 又会自动接上，之前手动禁用轨道反而和服务端竞态，偶发播不出来。
     */
    private fun applyVideoTrack() {
        val disabled = audioOnly
        if (videoTrackDisabled == disabled) return
        videoTrackDisabled = disabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
            .build()
    }

    private fun startProgressUpdates() {
        mainHandler.removeCallbacks(progressTick)
        mainHandler.post(progressTick)
    }

    private fun stopProgressUpdates() {
        mainHandler.removeCallbacks(progressTick)
        // 收尾再刷一次，让通知停在最终进度上
        mediaSession?.let { onUpdateNotification(it, /* startInForeground = */ false) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressTick)
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_SET_CONFIG, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_SET_CONFIG) {
                val track = MediaTrack.fromId(args.getString(EXTRA_TRACK))
                val audioOnly = args.getBoolean(EXTRA_AUDIO_ONLY, prefs.audioOnly)
                val speed = args.getFloat(EXTRA_SPEED, prefs.speed)
                val play = args.getBoolean(EXTRA_PLAY, player.isPlaying)
                applyConfig(track, audioOnly, speed, play)
                prefs.track = track
                prefs.audioOnly = audioOnly
                prefs.speed = speed
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    companion object {
        /** 本地 asset 的读取上限，6MB 足够覆盖 20 秒缓冲 */
        private const val TARGET_BUFFER_BYTES = 6 * 1024 * 1024

        /** 通知/锁屏 ±10 秒按钮的步长 */
        const val SEEK_INCREMENT_MS = 10_000L

        /** 通知进度刷新间隔；暂停时不刷 */
        private const val PROGRESS_UPDATE_MS = 1_000L

        private const val ARTWORK_SIZE_PX = 192

        const val ACTION_CONFIG = "zhiqiu.kongkim.action.CONFIG"
        const val ACTION_ALARM = "zhiqiu.kongkim.action.ALARM"
        const val ACTION_TOGGLE = "zhiqiu.kongkim.action.TOGGLE"
        const val EXTRA_TRACK = "track"
        const val EXTRA_AUDIO_ONLY = "audio_only"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_PLAY = "play"
        const val COMMAND_SET_CONFIG = "set_config"

        fun configIntent(
            context: android.content.Context,
            track: MediaTrack,
            audioOnly: Boolean,
            speed: Float,
            play: Boolean = true,
            alarm: Boolean = false
        ): Intent = Intent(context, PlaybackService::class.java).apply {
            action = if (alarm) ACTION_ALARM else ACTION_CONFIG
            putExtra(EXTRA_TRACK, track.id)
            putExtra(EXTRA_AUDIO_ONLY, audioOnly)
            putExtra(EXTRA_SPEED, speed)
            putExtra(EXTRA_PLAY, play)
        }

        /** 小窗播放/暂停按钮 */
        fun toggleIntent(context: android.content.Context): Intent =
            Intent(context, PlaybackService::class.java).setAction(ACTION_TOGGLE)
    }
}
