package com.nohimazin.tinytransit.data.realtime

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
    val feedTimestamp: Long?
)