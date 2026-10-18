package zhiqiu.kongkim.media

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * 锁屏 / 通知栏的播放卡片，在默认通知基础上补齐两件事：
 * - 默认通知只有 上一首/下一首，单曲场景没用；换成 ±10 秒按钮（依赖 ExoPlayer 的
 *   seekBack/seekForward increment，见 [PlaybackService]）
 * - 挂一条进度条 + “版本 · 还剩 xx:xx”，锁屏也能看到练到哪了
 *
 * 进度是静态的，由 [PlaybackService] 在播放中每秒调 onUpdateNotification 刷新。
 */
@OptIn(UnstableApi::class)
class PlaybackNotificationProvider(context: Context) :
    DefaultMediaNotificationProvider(context) {

    private var session: MediaSession? = null

    override fun getMediaButtons(
        session: MediaSession,
        commands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        mediaButtonEnabled: Boolean,
    ): ImmutableList<CommandButton> {
        this.session = session
        val base = super.getMediaButtons(session, commands, customLayout, mediaButtonEnabled)
        // 单曲没有上一首/下一首，只保留播放暂停，前后补上 ±10 秒
        val playPause = base.filter { it.playerCommand == Player.COMMAND_PLAY_PAUSE }
        val incrementSeconds = session.player.seekBackIncrement / 1000L
        val rewind = if (commands.contains(Player.COMMAND_SEEK_BACK)) {
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setDisplayName("后退 $incrementSeconds 秒")
                .build()
        } else {
            null
        }
        val forward = if (commands.contains(Player.COMMAND_SEEK_FORWARD)) {
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setDisplayName("快进 $incrementSeconds 秒")
                .build()
        } else {
            null
        }
        return ImmutableList.builder<CommandButton>()
            .addAll(listOfNotNull(rewind))
            .addAll(playPause)
            .addAll(listOfNotNull(forward))
            .build()
    }

    override fun addNotificationActions(
        session: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        this.session = session
        val compactIndices =
            super.addNotificationActions(session, mediaButtons, builder, actionFactory)
        val duration = session.player.duration
        if (duration != C.TIME_UNSET && duration > 0L) {
            builder.setProgress(
                /* max = */ duration.toInt(),
                /* progress = */ session.player.currentPosition
                    .coerceIn(0L, duration)
                    .toInt(),
                /* indeterminate = */ false
            )
        }
        return compactIndices
    }

    override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? {
        val player = session?.player ?: return super.getNotificationContentText(metadata)
        val duration = player.duration
        val trackLabel = metadata.artist ?: metadata.title
            ?: return super.getNotificationContentText(metadata)
        if (duration == C.TIME_UNSET || duration <= 0L) return trackLabel
        val remainingMs = (duration - player.currentPosition).coerceAtLeast(0L)
        return "$trackLabel · 还剩 ${formatClock(remainingMs)}"
    }

    private fun formatClock(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }
}
