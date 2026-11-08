package com.nohimazin.tinytransit.data.realtime

import android.content.Context
import com.google.transit.realtime.GtfsRealtime
import com.nohimazin.tinytransit.data.db.TinyTransitDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
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
        // 事業者のタイムゾーンを DB から取得
        val agencyTimezone = database.feedDao().get(feedId)?.agencyTimezone

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

                if (predictions.size < 3) {
                    Log.d("GtfsRT", "trip=$tripId stop=$stopId seq=$stopSequence hasArrival=${stu.hasArrival()} time=${arrivalEvent?.time} delay=${arrivalEvent?.delay} hasDeparture=${stu.hasDeparture()}")
                }

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
                        feedTimestamp = feedTimestamp,
                        agencyTimezone = agencyTimezone
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
     * GTFS-Realtime と静的 GTFS の照合結果を検証します。
     */
    suspend fun verifyRealtimeIntegrity(
        feedId: String,
        url: String
    ): GtfsRealtimeVerificationResult = withContext(Dispatchers.IO) {
        val predictions = fetchAndDecodeTripUpdates(feedId, url)

        // trip_id の照合
        val routeIds = database.routeDao().getAll(feedId).map { it.routeId }.toSet()
        val allTrips = database.routeDao().getAll(feedId).flatMap { r ->
            database.tripDao().getByRoute(feedId, r.routeId)
        }
        val tripIds = allTrips.map { it.tripId }.toSet()

        var tripMatched = 0
        var tripUnmatched = 0
        val tripUpdateCount = predictions.map { it.tripId }.distinct().size

        for (tripId in predictions.map { it.tripId }.distinct()) {
            if (tripId in tripIds) tripMatched++ else tripUnmatched++
        }

        // StopTimeUpdate の照合
        var stopMatched = 0
        var stopSeqMismatch = 0
        var stopIdMismatch = 0
        var stopUnmatched = 0
        var bothConsistent = 0
        var bothInconsistent = 0
        var skippedCount = 0
        var canceledCount = 0
        var predictedValid = 0
        var predictedInvalid = 0

        for (p in predictions) {
            if (p.isSkipped) skippedCount++
            if (p.isCanceled) canceledCount++

            // predicted time の妥当性
            if (p.predictedArrivalEpoch != null && p.predictedArrivalEpoch > 0) {
                predictedValid++
            } else {
                predictedInvalid++
            }

            val stopTimes = database.stopTimeDao().getByTrip(feedId, p.tripId)
            if (stopTimes.isEmpty()) {
                stopUnmatched++
                continue
            }

            // stop_sequence で照合
            val bySeq = if (p.stopSequence != null) {
                stopTimes.firstOrNull { it.stopSequence == p.stopSequence }
            } else null

            // stop_id で照合
            val byId = if (p.stopId.isNotEmpty()) {
                stopTimes.firstOrNull { it.stopId == p.stopId }
            } else null

            val seqOk = bySeq != null
            val idOk = byId != null

            if (seqOk && idOk) {
                // 両方存在する場合、同じ停車情報を指すか
                if (bySeq!!.stopId == p.stopId || byId!!.stopSequence == p.stopSequence) {
                    bothConsistent++
                } else {
                    bothInconsistent++
                }
                stopMatched++
            } else if (seqOk) {
                stopMatched++
                if (!idOk) stopIdMismatch++
            } else if (idOk) {
                stopMatched++
                if (!seqOk) stopSeqMismatch++
            } else {
                stopUnmatched++
            }
        }

        GtfsRealtimeVerificationResult(
            feedId = feedId,
            tripUpdateCount = tripUpdateCount,
            stopTimeUpdateCount = predictions.size,
            tripIdMatched = tripMatched,
            tripIdUnmatched = tripUnmatched,
            stopMatched = stopMatched,
            stopSequenceMismatch = stopSeqMismatch,
            stopIdMismatch = stopIdMismatch,
            stopUnmatched = stopUnmatched,
            stopBothConsistent = bothConsistent,
            stopBothInconsistent = bothInconsistent,
            skippedCount = skippedCount,
            canceledCount = canceledCount,
            predictedTimeValid = predictedValid,
            predictedTimeInvalid = predictedInvalid,
            isConsistent = tripUnmatched == 0 && stopUnmatched == 0 && bothInconsistent == 0
        )
    }

    /**
     * 特定停留所の到着予測を取得し、静的 GTFS の路線・便情報を付与して返します。
     */
    suspend fun getArrivalsForStop(
        feedId: String,
        stopId: String,
        url: String
    ): List<StopArrival> = withContext(Dispatchers.IO) {
        val predictions = fetchAndDecodeTripUpdates(feedId, url)
            .filter { it.stopId == stopId }

        predictions.map { p ->
            val trip = database.tripDao().get(feedId, p.tripId)
            val route = trip?.let { database.routeDao().get(feedId, it.routeId) }
            val stop = database.stopDao().get(feedId, p.stopId)
            StopArrival(
                prediction = p,
                stopName = stop?.stopName,
                routeShortName = route?.routeShortName,
                routeLongName = route?.routeLongName,
                tripHeadsign = trip?.tripHeadsign
            )
        }.sortedBy { it.prediction.effectivePredictedEpoch(0) ?: Long.MAX_VALUE }
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