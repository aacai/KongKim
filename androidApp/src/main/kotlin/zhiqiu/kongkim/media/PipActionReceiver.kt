package zhiqiu.kongkim.media

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 小窗（PiP）上的播放/暂停按钮，转发给播放服务 */
class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PlaybackService.ACTION_TOGGLE) {
            context.startService(PlaybackService.toggleIntent(context))
        }
    }

    companion object {
        private const val REQUEST_TOGGLE = 1002

        fun togglePendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_TOGGLE,
                Intent(context, PipActionReceiver::class.java)
                    .setAction(PlaybackService.ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
