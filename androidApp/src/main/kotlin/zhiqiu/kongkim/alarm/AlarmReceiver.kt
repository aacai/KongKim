package zhiqiu.kongkim.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import zhiqiu.kongkim.media.AppPreferences
import zhiqiu.kongkim.media.MediaTrack
import zhiqiu.kongkim.media.PlaybackService

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_FIRE) return
        val prefs = AppPreferences(context)
        val track = MediaTrack.fromId(
            intent.getStringExtra(EXTRA_TRACK) ?: prefs.alarmTrack.id
        )
        val serviceIntent = PlaybackService.configIntent(
            context = context,
            track = track,
            audioOnly = true,
            speed = prefs.speed,
            play = true,
            alarm = true
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        // 每天同一时间再响一次
        if (prefs.alarmEnabled) {
            AlarmScheduler.schedule(
                context,
                prefs.alarmHour,
                prefs.alarmMinute,
                prefs.alarmTrack
            )
        }
    }

    companion object {
        const val ACTION_FIRE = "zhiqiu.kongkim.action.ALARM_FIRE"
        const val EXTRA_TRACK = "track"
    }
}
