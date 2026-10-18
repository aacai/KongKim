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
        if (intent?.action != AlarmScheduler.ACTION_FIRE) return
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
        val prefs = AppPreferences(context)
        val alarm = prefs.alarms.find { it.id == id } ?: return
        // 先把这一条排到下一次合法时间（若刚被关闭，scheduleOne 会撤销它）
        AlarmScheduler.scheduleOne(context, alarm)
        if (!alarm.enabled) return
        val serviceIntent = PlaybackService.configIntent(
            context = context,
            track = alarm.track,
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
    }
}
