package zhiqiu.kongkim.media

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 闹钟重复规则的计算：星期匹配、大小周奇偶、节假日跳过、下一次触发时间。
 * 全部是纯函数，方便单测，也不依赖 Android 框架。
 */
object AlarmRules {

    /**
     * 大小周的基准周一。具体哪一周算“大周”由 [bigWeekParityValue] 校准，
     * 所以这里只要保证基准是周一、且相邻周奇偶相反即可。
     */
    private val WEEK_EPOCH: Long = run {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2024)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        cal.timeInMillis
    }

    /** 日期所在周的奇偶：0 或 1（用正午计算，避开夏令时边界） */
    fun weekParity(date: Calendar): Int {
        val cal = date.clone() as Calendar
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val days = (cal.timeInMillis - WEEK_EPOCH) / (24L * 3600 * 1000)
        val p = (days / 7) % 2
        return if (p < 0) (p + 2).toInt() else p.toInt()
    }

    fun isBigWeek(date: Calendar, bigWeekParityValue: Int): Boolean =
        weekParity(date) == bigWeekParityValue

    /** 本周（今天所在周）的奇偶，用于“本周是大周”校准开关 */
    fun currentWeekParity(): Int = weekParity(Calendar.getInstance())

    /** 该闹钟在某天是否应当响 */
    fun matches(
        alarm: AlarmItem,
        date: Calendar,
        holidays: Set<String>,
        bigWeekParityValue: Int,
    ): Boolean {
        if (!alarm.enabled) return false
        if (alarm.repeatDays.isEmpty()) return false
        if (date.get(Calendar.DAY_OF_WEEK) !in alarm.repeatDays) return false
        when (alarm.weekParity) {
            WeekParity.BIG_WEEK -> if (!isBigWeek(date, bigWeekParityValue)) return false
            WeekParity.SMALL_WEEK -> if (isBigWeek(date, bigWeekParityValue)) return false
            WeekParity.EVERY -> Unit
        }
        if (alarm.skipOnHoliday && isHoliday(date, holidays)) return false
        return true
    }

    /**
     * 从 [now] 往后找第一个满足规则的触发时刻（含今天若时间未过）。
     * 找不到返回 null（极端情况下规则永远不满足，比如大小周+某天被排除了）。
     */
    fun nextTriggerMillis(
        alarm: AlarmItem,
        now: Long,
        holidays: Set<String>,
        bigWeekParityValue: Int,
    ): Long? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        repeat(MAX_LOOKAHEAD_DAYS) {
            if (matches(alarm, cal, holidays, bigWeekParityValue)) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, alarm.hour)
            cal.set(Calendar.MINUTE, alarm.minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
        }
        return null
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun formatDate(cal: Calendar): String = synchronized(dateFormat) { dateFormat.format(cal.time) }

    fun isHoliday(date: Calendar, holidays: Set<String>): Boolean = formatDate(date) in holidays

    fun isHoliday(timeMillis: Long, holidays: Set<String>): Boolean =
        isHoliday(Calendar.getInstance().apply { this.timeInMillis = timeMillis }, holidays)

    private const val MAX_LOOKAHEAD_DAYS = 732 // ~2 年上限，避免规则异常时死循环
}
