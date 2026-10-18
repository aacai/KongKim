package zhiqiu.kongkim.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import zhiqiu.kongkim.MainActivity
import zhiqiu.kongkim.media.AlarmItem
import zhiqiu.kongkim.media.AlarmRules
import zhiqiu.kongkim.media.AppPreferences

object AlarmScheduler {
    const val ACTION_FIRE = "zhiqiu.kongkim.action.ALARM_FIRE"
    const val EXTRA_ALARM_ID = "alarm_id"

    /** 全量重排：撤销全部再重挂启用的，避免增删/改规则后残留旧 PendingIntent */
    fun scheduleAll(context: Context) {
        val prefs = AppPreferences(context)
        prefs.alarms.forEach { cancel(context, it) }
        prefs.alarms.filter { it.enabled }.forEach { scheduleOne(context, it) }
    }

    /** 重排单条（新增/编辑/触发后调用） */
    fun scheduleOne(context: Context, alarm: AlarmItem) {
        val prefs = AppPreferences(context)
        cancel(context, alarm)
        if (!alarm.enabled) return
        val trigger = AlarmRules.nextTriggerMillis(
            alarm,
            System.currentTimeMillis(),
            prefs.holidays,
            prefs.bigWeekParityValue
        ) ?: return
        setClock(context, alarm, trigger)
    }

    private fun setClock(context: Context, alarm: AlarmItem, trigger: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 系统时钟 App 里显示的小窗意图，点通知能回到应用
        val showIntent = PendingIntent.getActivity(
            context,
            requestCode(alarm) + 1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(trigger, showIntent),
            pendingIntent(context, alarm)
        )
    }

    fun cancel(context: Context, alarm: AlarmItem) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context, alarm))
    }

    private fun pendingIntent(context: Context, alarm: AlarmItem): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(alarm),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 每条闹钟用稳定的 requestCode，避免撤销/重排时误伤其它闹钟 */
    private fun requestCode(alarm: AlarmItem): Int = alarm.id.hashCode() and 0x7FFFFFFF

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }
}
