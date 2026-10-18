package zhiqiu.kongkim.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import zhiqiu.kongkim.MainActivity

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var prefs: AppPreferences
    private var audioOnly = false
    private var screenOn = true
    private var videoTrackDisabled = false
    /** 播完后 stop() 会保留结尾位置，重新点播时必须先重建 media item 才能回到开头 */
    private var stoppedAtEnd = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // 练完整套就交还解码器和缓冲队列，别让几十 MB 挂在后台
            if (playbackState == Player.STATE_ENDED) {
                stoppedAtEnd = true
                player.stop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(zhiqiu.kongkim.R.string.app_name)
                .build()
        )
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
                if (intent.hasExtra(EXTRA_SCREEN_ON)) {
                    screenOn = intent.getBooleanExtra(EXTRA_SCREEN_ON, true)
                }
                applyConfig(track, audioOnly, speed, play)
                if (intent.action != ACTION_ALARM) {
                    prefs.track = track
                    prefs.audioOnly = audioOnly
                    prefs.speed = speed
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
        val asset = track.assetFile(audioOnly)
        val uri = "asset:///$asset"
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
            player.setMediaItem(
                MediaItem.fromUri(uri).buildUpon().setMediaId(track.id).build()
            )
            player.prepare()
            if (resumeAt != C.TIME_UNSET && resumeAt > 0) {
                player.seekTo(resumeAt)
            }
        } else if (player.playbackState == Player.STATE_IDLE) {
            // 播完后 stop() 释放了解码器，重新点播要先 prepare；
            // 停在结尾时重建 media item，从头开始而不是原地重播
            if (stoppedAtEnd) {
                stoppedAtEnd = false
                player.setMediaItem(
                    MediaItem.fromUri(uri).buildUpon().setMediaId(track.id).build()
                )
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

    /**
     * 只听声音、或者界面已经不可见（息屏/切后台，Surface 被系统销毁）时撤掉视频轨道：
     * 解码器继续往没了的 Surface 上吐帧会把输出队列堵死，声音也就跟着停了。
     * 只在开关真的变了时才改参数，重写一次就会让解码器重启、画面闪一下。
     */
    private fun applyVideoTrack() {
        val disabled = audioOnly || !screenOn
        if (videoTrackDisabled == disabled) return
        videoTrackDisabled = disabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
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
                screenOn = args.getBoolean(EXTRA_SCREEN_ON, screenOn)
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

        const val ACTION_CONFIG = "zhiqiu.kongkim.action.CONFIG"
        const val ACTION_ALARM = "zhiqiu.kongkim.action.ALARM"
        const val EXTRA_TRACK = "track"
        const val EXTRA_AUDIO_ONLY = "audio_only"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_PLAY = "play"
        const val EXTRA_SCREEN_ON = "screen_on"
        const val COMMAND_SET_CONFIG = "set_config"

        fun configIntent(
            context: android.content.Context,
            track: MediaTrack,
            audioOnly: Boolean,
            speed: Float,
            play: Boolean = true,
            alarm: Boolean = false,
            screenOn: Boolean = true
        ): Intent = Intent(context, PlaybackService::class.java).apply {
            action = if (alarm) ACTION_ALARM else ACTION_CONFIG
            putExtra(EXTRA_TRACK, track.id)
            putExtra(EXTRA_AUDIO_ONLY, audioOnly)
            putExtra(EXTRA_SPEED, speed)
            putExtra(EXTRA_PLAY, play)
            putExtra(EXTRA_SCREEN_ON, screenOn)
        }
    }
}
