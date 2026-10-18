package zhiqiu.kongkim.media

import java.util.Calendar

/** 周奇偶：每周都响 / 仅大周 / 仅小周 */
enum class WeekParity {
    EVERY, BIG_WEEK, SMALL_WEEK
}

/**
 * 一条闹钟：独立的时间、练习版本、重复规则。
 * [repeatDays] 用 Calendar 的星期常量（SUNDAY=1 … SATURDAY=7），空集合视为“不响任何一天”。
 */
data class AlarmItem(
    val id: String,
    val hour: Int,
    val minute: Int,
    val track: MediaTrack,
    val enabled: Boolean = true,
    val repeatDays: Set<Int> = DEFAULT_DAYS,
    val weekParity: WeekParity = WeekParity.EVERY,
    val skipOnHoliday: Boolean = false,
    val label: String = "",
) {
    companion object {
        /** 默认每天（周一到周日全选） */
        val DEFAULT_DAYS: Set<Int> = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()
    }
}
