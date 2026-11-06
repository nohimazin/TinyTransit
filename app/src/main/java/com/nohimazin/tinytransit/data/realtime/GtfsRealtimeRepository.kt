package com.nohimazin.tinytransit.data.realtime

import android.content.Context
import com.google.transit.realtime.GtfsRealtime
import com.nohimazin.tinytransit.data.db.TinyTransitDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * GTFS-Realtime (TripUpdate) の取得・デコード・照合を担うリポジトリ。
 */
class GtfsRealtimeRepository(
    private val context: Context,
    private val database: TinyTransitDatabase,
    private val httpClient: OkHttpClient
) {
    /**
     * TripUpdate フィードを取得し、デコードして到着予測リストを返します。
     *
     * @param feedId 事業者識別子
     * @param url TripUpdate の URL
     * @return 到着予測のリスト
     */
    suspend fun fetchAndDecodeTripUpdates(
        feedId: String,
        url: String
    ): List<ArrivalPrediction> = withContext(Dispatchers.IO) {
        // 1. HTTP 取得
        val request = Request.Builder().url(url).build()
        val bytes = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body!!.bytes()
        }

        // デバッグ用に保存
        File(context.cacheDir, "trip_update_$feedId.bin").writeBytes(bytes)

        // 2. Protobuf デコード
        val feedMessage = GtfsRealtime.FeedMessage.parseFrom(bytes)

        // 3. TripUpdate → ArrivalPrediction に変換
        val predictions = mutableListOf<ArrivalPrediction>()
        val feedTimestamp = feedMessage.header.timestamp.takeIf { it > 0 }

        for (entity in feedMessage.entityList) {
            if (!entity.hasTripUpdate()) continue
            val tripUpdate = entity.tripUpdate
            val tripDescriptor = tripUpdate.trip
            val tripId = tripDescriptor.tripId.takeIf { it.isNotEmpty() } ?: continue

            // TripUpdate の schedule_relationship
            val scheduleRel = tripDescriptor.scheduleRelationship

            // CANCELED / DELETED の便も記録する
            val isCanceled = scheduleRel == GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED ||
                scheduleRel == GtfsRealtime.TripDescriptor.ScheduleRelationship.DELETED

            val routeId = tripDescriptor.routeId.takeIf { it.isNotEmpty() }

            for (stu in tripUpdate.stopTimeUpdateList) {
                val stopId = stu.stopId.takeIf { it.isNotEmpty() } ?: ""
                val stopSequence = if (stu.hasStopSequence()) stu.stopSequence.toInt() else null

                val isSkipped = stu.scheduleRelationship == GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED

                val arrivalEvent = if (stu.hasArrival()) stu.arrival else null
                val predictedArrival = arrivalEvent?.time?.takeIf { it > 0 }
                val delay = arrivalEvent?.delay

                // 静的 GTFS の scheduled arrival を取得
                val scheduledArrival = getStaticScheduledArrival(feedId, tripId, stopSequence, stopId)

                predictions.add(
                    ArrivalPrediction(
                        feedId = feedId,
                        tripId = tripId,
                        routeId = routeId,
                        stopId = stopId,
                        stopSequence = stopSequence,
                        scheduledArrivalSeconds = scheduledArrival,
                        predictedArrivalEpoch = predictedArrival,
                        delaySeconds = delay,
                        isSkipped = isSkipped,
                        isCanceled = isCanceled,
                        feedTimestamp = feedTimestamp
                    )
                )
            }
        }

        predictions
    }

    /**
     * 静的 GTFS から該当便・停留所の scheduled arrival time (秒) を取得します。
     * GTFS の時刻は "HH:MM:SS" 形式（24h+ 対応）。
     */
    private suspend fun getStaticScheduledArrival(
        feedId: String,
        tripId: String,
        stopSequence: Int?,
        stopId: String
    ): Long? {
        val stopTimes = database.stopTimeDao().getByTrip(feedId, tripId)
        val st = if (stopSequence != null) {
            stopTimes.firstOrNull { it.stopSequence == stopSequence }
        } else {
            stopTimes.firstOrNull { it.stopId == stopId }
        } ?: return null
        val timeStr = st.arrivalTime ?: st.departureTime ?: return null
        return parseGtfsTimeToSeconds(timeStr)
    }

    /**
     * GTFS 時刻文字列 "HH:MM:SS" をサービス日開始からの経過秒数に変換します。
     * 24 時間超（例: 25:10:00）にも対応します。
     */
    private fun parseGtfsTimeToSeconds(time: String): Long? {
        val parts = time.split(":")
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toLongOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }
}