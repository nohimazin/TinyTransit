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
    val agencyTimezone: String? = null
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
     */
    fun delayText(): String {
        if (isCanceled) return "運休"
        if (isSkipped) return "通過"
        val d = delaySeconds ?: return "定刻"
        return when {
            d == 0 -> "定刻"
            d > 0 -> "${d / 60}分遅れ"
            else -> "${-d / 60}分早い"
        }
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