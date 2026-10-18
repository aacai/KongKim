package zhiqiu.kongkim.media

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import zhiqiu.kongkim.media.AlarmItem
import zhiqiu.kongkim.media.WeekParity
import java.util.Calendar
import java.util.UUID

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var track: MediaTrack
        get() = MediaTrack.fromId(prefs.getString(KEY_TRACK, MediaTrack.NINE.id))
        set(value) = prefs.edit().putString(KEY_TRACK, value.id).apply()

    var audioOnly: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_ONLY, value).apply()

    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1f)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value).apply()

    /** 视频模式下按 Home 退出应用时，是否自动进小窗（PiP）继续播放 */
    var pipEnabled: Boolean
        get() = prefs.getBoolean(KEY_PIP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PIP_ENABLED, value).apply()

    var alarmEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALARM_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ALARM_ENABLED, value).apply()

    var alarmHour: Int
        get() = prefs.getInt(KEY_ALARM_HOUR, 6)
        set(value) = prefs.edit().putInt(KEY_ALARM_HOUR, value).apply()

    var alarmMinute: Int
        get() = prefs.getInt(KEY_ALARM_MINUTE, 30)
        set(value) = prefs.edit().putInt(KEY_ALARM_MINUTE, value).apply()

    var alarmTrack: MediaTrack
        get() = MediaTrack.fromId(prefs.getString(KEY_ALARM_TRACK, MediaTrack.NINE.id))
        set(value) = prefs.edit().putString(KEY_ALARM_TRACK, value.id).apply()

    /** 闹钟列表（取代原来的单一每日闹钟） */
    var alarms: List<AlarmItem>
        get() {
            val raw = prefs.getStringSet(KEY_ALARMS, null)
            if (raw == null) return migrateLegacyAlarm()
            return raw.mapNotNull { decodeAlarm(it) }
        }
        set(value) = prefs.edit()
            .putStringSet(KEY_ALARMS, value.map { encodeAlarm(it) }.toSet())
            .apply()

    /** 节假日集合，存 "yyyy-MM-dd" */
    var holidays: Set<String>
        get() = prefs.getStringSet(KEY_HOLIDAYS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_HOLIDAYS, value).apply()

    /** 大小周校准：0/1 哪个奇偶算“大周” */
    var bigWeekParityValue: Int
        get() = prefs.getInt(KEY_BIG_WEEK_PARITY, 0)
        set(value) = prefs.edit().putInt(KEY_BIG_WEEK_PARITY, value and 1).apply()

    /** 把旧版单个闹钟迁移成“一条每天响的闹钟”，保证老用户设置不丢 */
    private fun migrateLegacyAlarm(): List<AlarmItem> {
        if (!prefs.contains(KEY_ALARM_ENABLED) && !prefs.contains(KEY_ALARM_HOUR)) {
            return emptyList()
        }
        val legacy = AlarmItem(
            id = UUID.randomUUID().toString(),
            hour = prefs.getInt(KEY_ALARM_HOUR, 6),
            minute = prefs.getInt(KEY_ALARM_MINUTE, 30),
            track = MediaTrack.fromId(prefs.getString(KEY_ALARM_TRACK, MediaTrack.NINE.id)),
            enabled = prefs.getBoolean(KEY_ALARM_ENABLED, false)
        )
        alarms = listOf(legacy)
        return listOf(legacy)
    }

    private fun encodeAlarm(a: AlarmItem): String = JSONObject().apply {
        put("id", a.id)
        put("h", a.hour)
        put("m", a.minute)
        put("t", a.track.id)
        put("e", if (a.enabled) 1 else 0)
        put("d", a.repeatDays.joinToString(","))
        put("p", a.weekParity.ordinal)
        put("s", if (a.skipOnHoliday) 1 else 0)
        put("l", a.label)
    }.toString()

    private fun decodeAlarm(s: String): AlarmItem? = runCatching {
        val o = JSONObject(s)
        AlarmItem(
            id = o.getString("id"),
            hour = o.getInt("h"),
            minute = o.getInt("m"),
            track = MediaTrack.fromId(o.optString("t", MediaTrack.NINE.id)),
            enabled = o.optInt("e", 1) == 1,
            repeatDays = o.optString("d", "").takeIf { it.isNotEmpty() }
                ?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: AlarmItem.DEFAULT_DAYS,
            weekParity = WeekParity.entries.getOrElse(o.optInt("p", 0)) { WeekParity.EVERY },
            skipOnHoliday = o.optInt("s", 0) == 1,
            label = o.optString("l", "")
        )
    }.getOrNull()

    companion object {
        private const val PREFS_NAME = "kongkim"
        private const val KEY_TRACK = "track"
        private const val KEY_AUDIO_ONLY = "audio_only"
        private const val KEY_SPEED = "speed"
        private const val KEY_PIP_ENABLED = "pip_enabled"
        private const val KEY_ALARM_ENABLED = "alarm_enabled"
        private const val KEY_ALARM_HOUR = "alarm_hour"
        private const val KEY_ALARM_MINUTE = "alarm_minute"
        private const val KEY_ALARM_TRACK = "alarm_track"
        private const val KEY_ALARMS = "alarms"
        private const val KEY_HOLIDAYS = "holidays"
        private const val KEY_BIG_WEEK_PARITY = "big_week_parity"

        val SPEED_OPTIONS = listOf(1f, 1.1f, 1.25f, 1.5f)
    }
}
