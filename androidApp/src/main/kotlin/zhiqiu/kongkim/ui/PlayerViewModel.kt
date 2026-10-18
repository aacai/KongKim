package zhiqiu.kongkim.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutionException
import zhiqiu.kongkim.alarm.AlarmScheduler
import zhiqiu.kongkim.media.AppPreferences
import zhiqiu.kongkim.media.MediaTrack
import zhiqiu.kongkim.media.PlaybackService
import kotlin.math.abs

/** 可快退/快进的步长，单位秒 */
const val SKIP_SECONDS = 10

data class PlayerUiState(
    val track: MediaTrack = MediaTrack.NINE,
    val audioOnly: Boolean = false,
    val speed: Float = 1f,
    val isPlaying: Boolean = false,
    val isReady: Boolean = false,
    val errorMessage: String? = null,
    val durationMs: Long = 0L,
)

data class AlarmUiState(
    val enabled: Boolean = false,
    val hour: Int = 6,
    val minute: Int = 30,
    val track: MediaTrack = MediaTrack.NINE,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppPreferences(application)
    private var controller: MediaController? = null
    private var connectFuture: ListenableFuture<MediaController>? = null
    private var decoderRetries = 0

    private val _player = MutableStateFlow(
        PlayerUiState(
            track = prefs.track,
            audioOnly = prefs.audioOnly,
            speed = nearestSpeed(prefs.speed),
        )
    )
    val player: StateFlow<PlayerUiState> = _player.asStateFlow()

    /** 播放进度单独一条流：只有进度条订阅它，避免每秒两次重组整屏 */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _alarm = MutableStateFlow(
        AlarmUiState(prefs.alarmEnabled, prefs.alarmHour, prefs.alarmMinute, prefs.alarmTrack)
    )
    val alarm: StateFlow<AlarmUiState> = _alarm.asStateFlow()

    /** 供视频画面绑定；连接完成后 [PlayerUiState.isReady] 会变为 true */
    val sessionPlayer: Player? get() = controller

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromController()
        }

        override fun onPlayerError(error: PlaybackException) {
            // 部分 ROM 的硬解解码器会偶发失败，重新 prepare 通常即可恢复
            val wasPlaying = controller?.playWhenReady == true
            if (wasPlaying && isDecoderError(error) && decoderRetries < MAX_DECODER_RETRIES) {
                decoderRetries += 1
                viewModelScope.launch {
                    delay(400)
                    retry()
                }
                return
            }
            _player.update { it.copy(errorMessage = errorText(error), isPlaying = false) }
        }
    }

    init {
        connect()
        viewModelScope.launch {
            while (isActive) {
                // 状态变化走 onEvents 回调，这里只在真正播放时补一次进度，暂停就不做跨进程调用
                if (_player.value.isPlaying) publishPosition()
                delay(POSITION_POLL_MS)
            }
        }
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        connectFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onCleared()
    }

    /** 预加载播放服务，必须在界面可见时调用（Android 12+ 禁止后台 startService） */
    fun warmUpService() {
        val app = getApplication<Application>()
        val state = _player.value
        app.startService(
            PlaybackService.configIntent(
                context = app,
                track = state.track,
                audioOnly = state.audioOnly,
                speed = state.speed,
                play = false,
            )
        )
    }

    private fun connect() {
        val app = getApplication<Application>()
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        connectFuture = future
        future.addListener(
            {
                try {
                    val result = future.get()
                    controller = result
                    result.addListener(playerListener)
                    syncFromController()
                    _player.update { it.copy(isReady = true) }
                } catch (_: ExecutionException) {
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun syncFromController() {
        val c = controller ?: return
        val duration = c.duration
        val speed = nearestSpeed(c.playbackParameters.speed)
        if (c.playbackState == Player.STATE_READY) {
            decoderRetries = 0
        }
        publishPosition()
        _player.update {
            it.copy(
                isPlaying = c.isPlaying,
                // 播完一套后服务会 stop()，此时 duration 变回 unset，保留上一次的值进度条才不会塌
                durationMs = if (duration == C.TIME_UNSET || duration < 0) {
                    it.durationMs
                } else {
                    duration
                },
                speed = speed,
                errorMessage = if (c.playbackState == Player.STATE_READY ||
                    c.playbackState == Player.STATE_BUFFERING
                ) {
                    null
                } else {
                    it.errorMessage
                },
            )
        }
        if (speed != prefs.speed) {
            prefs.speed = speed
        }
    }

    private fun publishPosition() {
        val position = controller?.currentPosition ?: return
        if (position != C.TIME_UNSET && position >= 0) {
            _position.value = position
        }
    }

    private fun isDecoderError(error: PlaybackException): Boolean =
        error.errorCodeName.contains("DECOD", ignoreCase = true)

    private fun errorText(error: PlaybackException): String = when {
        isDecoderError(error) -> "解码失败，请切换播放模式后重试"
        error.errorCodeName.startsWith("ERROR_CODE_IO_") -> "资源读取失败"
        else -> "播放失败，请重试"
    }

    fun togglePlay() {
        val c = controller
        if (c == null) {
            pushConfig(play = true)
            return
        }
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            pushConfig(play = true)
            c.play()
        }
    }

    /** 播放出错后重新准备并继续 */
    fun retry() {
        _player.update { it.copy(errorMessage = null) }
        val c = controller ?: return
        c.prepare()
        c.play()
    }

    fun skipBackward() = skipBy(-SKIP_SECONDS)

    fun skipForward() = skipBy(SKIP_SECONDS)

    fun skipBy(seconds: Int) {
        val state = _player.value
        val limit = if (state.durationMs > 0) state.durationMs else Long.MAX_VALUE
        seekTo((_position.value + seconds * 1000L).coerceIn(0L, limit))
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        controller?.seekTo(target)
        _position.value = target
    }

    fun restart() = seekTo(0L)

    fun setTrack(track: MediaTrack) {
        if (track == _player.value.track) return
        _position.value = 0L
        _player.update { it.copy(track = track, durationMs = 0L) }
        pushConfig(play = _player.value.isPlaying)
    }

    fun setAudioOnly(audioOnly: Boolean) {
        if (audioOnly == _player.value.audioOnly) return
        _player.update { it.copy(audioOnly = audioOnly) }
        pushConfig(play = _player.value.isPlaying)
    }

    fun setSpeed(speed: Float) {
        val value = nearestSpeed(speed)
        if (value == _player.value.speed) return
        _player.update { it.copy(speed = value) }
        pushConfig(play = _player.value.isPlaying)
    }

    private fun pushConfig(play: Boolean) {
        val app = getApplication<Application>()
        val state = _player.value
        prefs.track = state.track
        prefs.audioOnly = state.audioOnly
        prefs.speed = state.speed
        val args = Bundle().apply {
            putString(PlaybackService.EXTRA_TRACK, state.track.id)
            putBoolean(PlaybackService.EXTRA_AUDIO_ONLY, state.audioOnly)
            putFloat(PlaybackService.EXTRA_SPEED, state.speed)
            putBoolean(PlaybackService.EXTRA_PLAY, play)
        }
        val c = controller
        if (c != null) {
            c.sendCustomCommand(
                SessionCommand(PlaybackService.COMMAND_SET_CONFIG, Bundle.EMPTY),
                args
            )
        } else {
            app.startService(
                PlaybackService.configIntent(app, state.track, state.audioOnly, state.speed, play)
            )
        }
    }

    fun setAlarmEnabled(enabled: Boolean) {
        _alarm.update { it.copy(enabled = enabled) }
        prefs.alarmEnabled = enabled
        persistAlarm()
    }

    fun setAlarmTime(hour: Int, minute: Int) {
        _alarm.update { it.copy(hour = hour, minute = minute) }
        prefs.alarmHour = hour
        prefs.alarmMinute = minute
        persistAlarm()
    }

    fun setAlarmTrack(track: MediaTrack) {
        _alarm.update { it.copy(track = track) }
        prefs.alarmTrack = track
        persistAlarm()
    }

    private fun persistAlarm() {
        val app = getApplication<Application>()
        val state = _alarm.value
        if (state.enabled) {
            AlarmScheduler.schedule(app, state.hour, state.minute, state.track)
        } else {
            AlarmScheduler.cancel(app)
        }
    }

    fun canScheduleExactAlarms(): Boolean = AlarmScheduler.canScheduleExact(getApplication())

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val app = getApplication<Application>()
            app.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${app.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    companion object {
        val SPEED_OPTIONS = AppPreferences.SPEED_OPTIONS

        /** 进度轮询间隔；暂停时不轮询 */
        private const val POSITION_POLL_MS = 250L

        private const val MAX_DECODER_RETRIES = 2

        fun nearestSpeed(speed: Float): Float =
            SPEED_OPTIONS.minBy { abs(it - speed) }

        fun formatSpeed(speed: Float): String =
            if (speed == speed.toLong().toFloat()) "${speed.toLong()}×" else "${speed}×"

        fun formatTime(positionMs: Long, durationMs: Long): String {
            val ms = if (positionMs > durationMs) durationMs else positionMs
            val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
    }
}
