package com.nohimazin.tinytransit.data.realtime

/**
 * 停留所の到着予測表示用データ。
 * 静的 GTFS の路線・便情報を付与した表示モデル。
 */
data class StopArrival(
    val prediction: ArrivalPrediction,
    val stopName: String?,
    val routeShortName: String?,
    val routeLongName: String?,
    val tripHeadsign: String?
)