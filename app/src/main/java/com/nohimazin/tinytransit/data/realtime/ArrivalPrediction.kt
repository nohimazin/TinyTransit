package com.nohimazin.tinytransit.data.realtime

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 到着予測データ。
 * Protobuf のクラスを UI 層に直接渡さず、TinyTransit 独自のデータクラスに変換します。
 */
data class ArrivalPrediction(
    val feedId: String,
    val tripId: String,
    val routeId: String?,
    val stopId: String,
    val stopSequence: Int?,
    val scheduledArrivalSeconds: Long?,
    val predictedArrivalEpoch: Long?,
    val delaySeconds: Int?,
    val isSkipped: Boolean,
    val isCanceled: Boolean,
    val feedTimestamp: Long?,
    val tripUpdateTimestamp: Long? = null,
    val agencyTimezone: String? = null,
    val isRealtime: Boolean = true
) {
    /**
     * 予測到着時刻のエポック秒を返します。
     * predictedArrivalEpoch が null の場合は scheduled + delay で算出します。
     */
    fun effectivePredictedEpoch(serviceDayStartEpoch: Long): Long? {
        predictedArrivalEpoch?.let { return it }
        val sched = scheduledArrivalSeconds ?: return null
        val delay = delaySeconds ?: 0
        return serviceDayStartEpoch + sched + delay
    }

    /**
     * 予測到着時刻を "HH:MM" 形式で返します。
     */
    fun predictedTimeText(): String {
        val epoch = predictedArrivalEpoch ?: return "--:--"
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        // GTFS-Realtime の time は Unix epoch (UTC)。事業者のタイムゾーンで表示する。
        sdf.timeZone = TimeZone.getTimeZone(agencyTimezone ?: "Asia/Tokyo")
        return sdf.format(Date(epoch * 1000))
    }

    /**
     * 遅延を人間可読な文字列で返します。
     * delaySeconds が null の場合は predictedArrivalEpoch と scheduledArrivalSeconds から算出します。
     */
    fun delayText(): String {
        if (isCanceled) return "運休"
        if (isSkipped) return "通過"
        // 静的時刻表ベースの場合は遅延不明
        if (!isRealtime) return "不明"
        val d = delaySeconds ?: computeDelay() ?: return "定刻"
        return when {
            d == 0 -> "定刻"
            d > 0 -> "${d / 60}分遅れ"
            else -> "${-d / 60}分早い"
        }
    }

    /**
     * predictedArrivalEpoch と scheduledArrivalSeconds から遅延秒数を算出します。
     * predictedArrivalEpoch (Unix epoch) を事業者のタイムゾーンでの
     * サービス日開始からの経過秒数に変換し、scheduled との差を求めます。
     */
    private fun computeDelay(): Int? {
        val epoch = predictedArrivalEpoch ?: return null
        val sched = scheduledArrivalSeconds ?: return null
        val tz = TimeZone.getTimeZone(agencyTimezone ?: "Asia/Tokyo")
        val cal = java.util.Calendar.getInstance(tz)
        cal.timeInMillis = epoch * 1000
        val predSeconds = cal.get(java.util.Calendar.HOUR_OF_DAY) * 3600 +
            cal.get(java.util.Calendar.MINUTE) * 60 +
            cal.get(java.util.Calendar.SECOND)
        // 日付をまたぐ場合の補正（predicted が翌日 0-3時、scheduled が 23-24時）
        var diff = predSeconds - sched.toInt()
        if (diff < -43200) diff += 86400
        if (diff > 43200) diff -= 86400
        return diff
    }

    /**
     * あと何分かを返します。負の場合は null。
     */
    fun minutesUntil(nowEpoch: Long): Int? {
        val pred = predictedArrivalEpoch ?: return null
        val diff = pred - nowEpoch
        if (diff < 0) return null
        return (diff / 60).toInt()
    }
}