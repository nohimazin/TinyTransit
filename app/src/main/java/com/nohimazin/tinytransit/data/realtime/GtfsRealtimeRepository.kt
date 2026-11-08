package com.nohimazin.tinytransit.data.realtime

import android.content.Context
import android.util.Log
import com.google.transit.realtime.GtfsRealtime
import com.nohimazin.tinytransit.data.db.TinyTransitDatabase
import com.nohimazin.tinytransit.data.repository.GtfsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * GTFS-Realtime (TripUpdate) の取得・デコード・照合を担うリポジトリ。
 * Single-flight パターンで重複取得を防止する。
 */
class GtfsRealtimeRepository(
    private val context: Context,
    private val database: TinyTransitDatabase,
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "GtfsRT"
    }

    // Application スコープ（Activity/Service のキャンセルが HTTP 通信に波及しないように）
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Single-flight: フィード単位で Deferred を管理
    private data class FetchKey(
        val feedId: String,
        val url: String
    )

    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<FetchKey, Deferred<FetchResult>>()

    /**
     * TripUpdate フィードを取得し、デコードして到着予測リストを返します。
     * Single-flight: 同じフィードへの同時要求は同じ通信結果を共有します。
     */
    suspend fun fetchAndDecodeTripUpdates(
        feedId: String,
        url: String
    ): List<ArrivalPrediction> {
        val result = fetch(feedId, url)
        return when (result) {
            is FetchResult.Success -> result.predictions
            else -> emptyList()
        }
    }

    /**
     * HTTP 取得 + デコードを実行し、FetchResult を返します。
     * Single-flight パターンで重複取得を防止します。
     */
    suspend fun fetch(feedId: String, url: String): FetchResult {
        val key = FetchKey(feedId, url)

        // Mutex で Deferred の取得/生成を保護
        val deferred = inFlightMutex.withLock {
            inFlight[key]?.let { existing ->
                if (existing.isActive) return@withLock existing
            }
            val newDeferred = applicationScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                doFetchAndDecode(feedId, url)
            }
            // 完了時にクリア（古い Deferred が新しいものを誤ってクリアしないよう参照比較）
            newDeferred.invokeOnCompletion {
                this@GtfsRealtimeRepository.applicationScope.launch {
                    inFlightMutex.withLock {
                        if (inFlight[key] === newDeferred) {
                            inFlight.remove(key)
                        }
                    }
                }
            }
            inFlight[key] = newDeferred
            newDeferred.start()
            newDeferred
        }

        // await は Mutex 外で実行（キャンセル伝播を防ぐ）
        return deferred.await()
    }

    /**
     * 実際の HTTP 取得・デコード処理。
     */
    private suspend fun doFetchAndDecode(feedId: String, url: String): FetchResult {
        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            // タイムアウトの判定
            if (!response.isSuccessful) {
                val bodyBytes = try { response.body?.bytes() } catch (e: Exception) { null }
                val receivedBytes = bodyBytes?.size?.toLong() ?: 0L

                // Retry-After のパース (429, 503)
                val retryAfterMs = if (response.code == 429 || response.code == 503) {
                    parseRetryAfter(response.header("Retry-After"))
                } else null

                return FetchResult.HttpError(
                    statusCode = response.code,
                    receivedBytes = receivedBytes,
                    retryAfterMs = retryAfterMs
                )
            }

            val bodyBytes = response.body?.bytes()
                ?: return FetchResult.NetworkError(IOException("Empty response body"))
            val receivedBytes = bodyBytes.size.toLong()

            // Protobuf デコード
            val feedMessage = try {
                GtfsRealtime.FeedMessage.parseFrom(bodyBytes)
            } catch (e: Exception) {
                Log.w(TAG, "Decode failed: ${e.message}")
                return FetchResult.DecodeError(e, receivedBytes)
            }

            // TripUpdate → ArrivalPrediction に変換
            val predictions = decodeTripUpdates(feedId, feedMessage)

            FetchResult.Success(receivedBytes, feedMessage, predictions)
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "Timeout: ${e.message}")
            FetchResult.Timeout(e)
        } catch (e: IOException) {
            Log.w(TAG, "Network error: ${e.message}")
            FetchResult.NetworkError(e)
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error: ${e.message}")
            FetchResult.NetworkError(e)
        }
    }

    /**
     * Protobuf の FeedMessage から ArrivalPrediction リストを生成します。
     */
    private suspend fun decodeTripUpdates(
        feedId: String,
        feedMessage: GtfsRealtime.FeedMessage
    ): List<ArrivalPrediction> {
        val predictions = mutableListOf<ArrivalPrediction>()
        val feedTimestamp = feedMessage.header.timestamp.takeIf { it > 0 }
        val agencyTimezone = database.feedDao().get(feedId)?.agencyTimezone

        for (entity in feedMessage.entityList) {
            if (!entity.hasTripUpdate()) continue
            val tripUpdate = entity.tripUpdate
            val tripDescriptor = tripUpdate.trip
            val tripId = tripDescriptor.tripId.takeIf { it.isNotEmpty() } ?: continue

            val scheduleRel = tripDescriptor.scheduleRelationship
            val isCanceled = scheduleRel == GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED ||
                scheduleRel == GtfsRealtime.TripDescriptor.ScheduleRelationship.DELETED

            val routeId = tripDescriptor.routeId.takeIf { it.isNotEmpty() }

            for (stu in tripUpdate.stopTimeUpdateList) {
                val stopId = stu.stopId.takeIf { it.isNotEmpty() } ?: ""
                val stopSequence = if (stu.hasStopSequence()) stu.stopSequence.toInt() else null

                val isSkipped = stu.scheduleRelationship ==
                    GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED

                val arrivalEvent = if (stu.hasArrival()) stu.arrival else null
                val predictedArrival = arrivalEvent?.time?.takeIf { it > 0 }
                val delay = arrivalEvent?.delay

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

        return predictions
    }

    /**
     * 静的 GTFS から該当便・停留所の scheduled arrival time (秒) を取得します。
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

    private fun parseGtfsTimeToSeconds(time: String): Long? {
        val parts = time.split(":")
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toLongOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }

    /**
     * Retry-After ヘッダーをパースしてミリ秒に変換します。
     * 秒数形式 ("120") と HTTP 日時形式 ("Wed, 21 Oct 2026 07:28:00 GMT") の両方に対応。
     */
    private fun parseRetryAfter(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        // 秒数形式
        header.trim().toLongOrNull()?.let { return it * 1000 }
        // HTTP 日時形式
        return try {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("GMT")
            val date = sdf.parse(header) ?: return null
            val diff = date.time - System.currentTimeMillis()
            if (diff > 0) diff else null
        } catch (e: Exception) {
            null
        }
    }

    // --- 既存メソッド (互換性維持) ---

    suspend fun getArrivalsForStop(
        feedId: String,
        stopId: String,
        url: String
    ): List<StopArrival> = withContext(Dispatchers.IO) {
        val result = fetch(feedId, url)
        val predictions = when (result) {
            is FetchResult.Success -> result.predictions
            else -> emptyList()
        }
        val baseId = GtfsRepository.baseStopId(stopId)
        val allStops = database.stopDao().getAll(feedId)
        val variantIds = allStops.filter { GtfsRepository.baseStopId(it.stopId) == baseId }
            .map { it.stopId }.toSet()
        val effectiveIds = if (variantIds.isEmpty()) setOf(stopId) else variantIds

        val nowEpoch = System.currentTimeMillis() / 1000
        predictions.filter { it.stopId in effectiveIds }
            // 過去の便を除外（予測到着時刻が現在より前のものは表示しない）
            .filter { p ->
                val pred = p.predictedArrivalEpoch ?: return@filter true
                pred >= nowEpoch - 60 // 1分前までは許容（更新タイミングのズレ対策）
            }
            .map { p ->
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

    suspend fun verifyRealtimeIntegrity(
        feedId: String,
        url: String
    ): GtfsRealtimeVerificationResult = withContext(Dispatchers.IO) {
        val result = fetch(feedId, url)
        val predictions = when (result) {
            is FetchResult.Success -> result.predictions
            else -> emptyList()
        }

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

            val bySeq = if (p.stopSequence != null) {
                stopTimes.firstOrNull { it.stopSequence == p.stopSequence }
            } else null

            val byId = if (p.stopId.isNotEmpty()) {
                stopTimes.firstOrNull { it.stopId == p.stopId }
            } else null

            val seqOk = bySeq != null
            val idOk = byId != null

            if (seqOk && idOk) {
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
}