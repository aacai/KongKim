package zhiqiu.kongkim

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import zhiqiu.kongkim.media.PipActionReceiver
import zhiqiu.kongkim.ui.HomeScreen
import zhiqiu.kongkim.ui.PlayerViewModel
import zhiqiu.kongkim.ui.theme.KongKimTheme

class MainActivity : ComponentActivity() {
    private val playerViewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleClosePip(intent)
        setContent {
            KongKimTheme {
                HomeScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleClosePip(intent)
    }

    /** 按 Home/侧滑回桌面：视频正在播就自动进小窗；只听声音时小窗没意义，交给通知控制 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            playerViewModel.shouldEnterPictureInPicture()
        ) {
            // 个别机型在非常规生命周期下调用会抛异常，兜住别崩
            runCatching { enterPictureInPictureMode(buildPipParams()) }
        }
    }

    @Deprecated("Deprecated in Java", ReplaceWith("onPictureInPictureModeChanged(isInPip, newConfig)"))
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        @Suppress("DEPRECATION")
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        playerViewModel.setPipMode(isInPictureInPictureMode)
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val playing = playerViewModel.player.value.isPlaying
            builder.setActions(
                listOf(
                    pipAction(
                        iconRes = if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
                        title = if (playing) "暂停" else "播放",
                        broadcast = PipActionReceiver.togglePendingIntent(this)
                    ),
                    pipAction(
                        iconRes = R.drawable.ic_pip_close,
                        title = "关闭",
                        activity = closePipPendingIntent()
                    )
                )
            )
        }
        return builder.build()
    }

    private fun pipAction(
        iconRes: Int,
        title: String,
        broadcast: PendingIntent? = null,
        activity: PendingIntent? = null,
    ): RemoteAction = RemoteAction(
        Icon.createWithResource(this, iconRes),
        title,
        title,
        broadcast ?: activity!!
    )

    /** 关闭小窗：把应用带回前台（PiP 随之退出）并停下播放 */
    private fun closePipPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_CLOSE_PIP,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_CLOSE_PIP, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun handleClosePip(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CLOSE_PIP, false) == true) {
            playerViewModel.pause()
        }
    }

    companion object {
        private const val REQUEST_CLOSE_PIP = 1001
        const val EXTRA_CLOSE_PIP = "close_pip"
    }
}
