package zhiqiu.kongkim.media

import android.content.Context
import android.content.SharedPreferences

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

    companion object {
        private const val PREFS_NAME = "kongkim"
        private const val KEY_TRACK = "track"
        private const val KEY_AUDIO_ONLY = "audio_only"
        private const val KEY_SPEED = "speed"
        private const val KEY_ALARM_ENABLED = "alarm_enabled"
        private const val KEY_ALARM_HOUR = "alarm_hour"
        private const val KEY_ALARM_MINUTE = "alarm_minute"
        private const val KEY_ALARM_TRACK = "alarm_track"

        val SPEED_OPTIONS = listOf(1f, 1.1f, 1.25f, 1.5f)
    }
}
