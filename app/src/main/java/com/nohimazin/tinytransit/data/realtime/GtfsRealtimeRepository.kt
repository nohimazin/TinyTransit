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
        url: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): List<ArrivalPrediction> {
        val result = fetch(feedId, url, onProgress)
        return when (result) {
            is FetchResult.Success -> result.predictions
            else -> emptyList()
        }
    }

    /**
     * HTTP 取得 + デコードを実行し、FetchResult を返します。
     * Single-flight パターンで重複取得を防止します。
     */
    suspend fun fetch(
        feedId: String,
        url: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): FetchResult {
        val key = FetchKey(feedId, url)

        // Mutex で Deferred の取得/生成を保護
        val deferred = inFlightMutex.withLock {
            inFlight[key]?.let { existing ->
                if (existing.isActive) return@withLock existing
            }
            val newDeferred = applicationScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                doFetchAndDecode(feedId, url, onProgress)
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
    private suspend fun doFetchAndDecode(
        feedId: String,
        url: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): FetchResult {
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

            val body = response.body ?: return FetchResult.NetworkError(IOException("Empty response body"))
            val contentLength = body.contentLength()
            val outputStream = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    totalRead += read
                    onProgress(totalRead, contentLength)
                }
            }
            val bodyBytes = outputStream.toByteArray()
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
        val tripCache = mutableMapOf<String, com.nohimazin.tinytransit.data.db.entity.TripEntity?>()
        val stopTimeCache = mutableMapOf<String, List<com.nohimazin.tinytransit.data.db.entity.StopTimeEntity>>()
        val routeCache = mutableMapOf<String, com.nohimazin.tinytransit.data.db.entity.RouteEntity?>()

        for (entity in feedMessage.entityList) {
            if (!entity.hasTripUpdate()) continue
            val tripUpdate = entity.tripUpdate
            val tripDescriptor = tripUpdate.trip
            val tripId = tripDescriptor.tripId.takeIf { it.isNotEmpty() } ?: continue

            val scheduleRel = tripDescriptor.scheduleRelationship
            val isCanceled = scheduleRel == GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED ||
                scheduleRel == GtfsRealtime.TripDescriptor.ScheduleRelationship.DELETED

            val routeId = tripDescriptor.routeId.takeIf { it.isNotEmpty() }

            // TripUpdate の個別タイムスタンプ (便のリアルタイム進捗計測時刻)
            val tripUpdateTimestamp = tripUpdate.timestamp.takeIf { it > 0 }

            val trip = tripCache.getOrPut(tripId) { database.tripDao().get(feedId, tripId) }
            val route = routeId?.let { rid -> routeCache.getOrPut(rid) { database.routeDao().get(feedId, rid) } }

            for (stu in tripUpdate.stopTimeUpdateList) {
                val stopId = stu.stopId.takeIf { it.isNotEmpty() } ?: ""
                val stopSequence = if (stu.hasStopSequence()) stu.stopSequence.toInt() else null

                val isSkipped = stu.scheduleRelationship ==
                    GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED

                val arrivalEvent = if (stu.hasArrival()) stu.arrival else null
                val predictedArrival = arrivalEvent?.time?.takeIf { it > 0 }
                val delay = arrivalEvent?.delay

                val stopTimes = stopTimeCache.getOrPut(tripId) { database.stopTimeDao().getByTrip(feedId, tripId) }
                val st = if (stopSequence != null) {
                    stopTimes.firstOrNull { it.stopSequence == stopSequence }
                } else {
                    stopTimes.firstOrNull { it.stopId == stopId }
                }
                val scheduledArrival = st?.let { st2 ->
                    val timeStr = st2.arrivalTime ?: st2.departureTime
                    timeStr?.let { parseGtfsTimeToSeconds(it) }
                }

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
                        tripUpdateTimestamp = tripUpdateTimestamp,
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

    private fun getServiceDayStartEpoch(timezone: String, scheduledSeconds: Long): Long {
        val tz = java.util.TimeZone.getTimeZone(timezone)
        val cal = java.util.Calendar.getInstance(tz)
        // 現在時刻を agency tz で取得
        val now = System.currentTimeMillis()
        cal.timeInMillis = now
        // 今日の 0:00 (midnight) を計算
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000
    }

    private fun getDayOfWeek(date: String): Int {
        val y = date.substring(0, 4).toInt()
        val m = date.substring(4, 6).toInt()
        val d = date.substring(6, 8).toInt()
        val cal = java.util.Calendar.getInstance()
        cal.set(y, m - 1, d)
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        return when (dow) {
            java.util.Calendar.MONDAY -> 1
            java.util.Calendar.TUESDAY -> 2
            java.util.Calendar.WEDNESDAY -> 3
            java.util.Calendar.THURSDAY -> 4
            java.util.Calendar.FRIDAY -> 5
            java.util.Calendar.SATURDAY -> 6
            java.util.Calendar.SUNDAY -> 7
            else -> 0
        }
    }

    private fun getServiceDayStartEpoch(timezone: String, scheduledSeconds: Long): Long {
        val tz = java.util.TimeZone.getTimeZone(timezone)
        val cal = java.util.Calendar.getInstance(tz)
        cal.timeInMillis = System.currentTimeMillis()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000
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
        url: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): List<StopArrival> = withContext(Dispatchers.IO) {
        val result = fetch(feedId, url, onProgress)
        val predictions = when (result) {
            is FetchResult.Success -> result.predictions
            else -> emptyList()
        }
        val baseId = GtfsRepository.baseStopId(stopId)
        val allStops = database.stopDao().getAll(feedId)
        val variantIds = allStops.filter { GtfsRepository.baseStopId(it.stopId) == baseId }
            .map { it.stopId }.toSet()
        val effectiveIds = if (variantIds.isEmpty()) setOf(stopId) else variantIds

        // 事前取得: 全 trips と routes をキャッシュ (DB クエリ削減)
        val allTrips = database.tripDao().getAll(feedId)
        val tripMap = allTrips.associateBy { it.tripId }
        val routeMap = database.routeDao().getAll(feedId).associateBy { it.routeId }

        val nowEpoch = System.currentTimeMillis() / 1000
        val agencyTz = database.feedDao().get(feedId)?.agencyTimezone ?: "Asia/Tokyo"
        // スケジュール済みだがリアルタイム予測がない便を補完
        val realtimeTripIds = predictions.map { it.tripId }.toSet()
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).apply {
            timeZone = java.util.TimeZone.getTimeZone(agencyTz)
        }.format(java.util.Date())
        val stopTimeEntries = effectiveIds.flatMap { sid ->
            database.stopTimeDao().getByStop(feedId, sid)
        }
        val scheduledOnly = mutableListOf<ArrivalPrediction>()
        for (tripId in stopTimeEntries.map { it.tripId }.distinct()) {
            if (tripId in realtimeTripIds) continue
            val trip = database.tripDao().get(feedId, tripId) ?: continue
            // isServiceActive のインライン実装
            val cal = database.calendarDao().getCalendar(feedId, trip.serviceId)
            val calDates = database.calendarDao().getCalendarDates(feedId, trip.serviceId)
            val exception = calDates.firstOrNull { it.date == today }
            val isActive = if (exception != null) {
                exception.exceptionType == 1
            } else if (cal == null) {
                false
            } else if (today < cal.startDate || today > cal.endDate) {
                false
            } else {
                val dow = getDayOfWeek(today)
                when (dow) {
                    1 -> cal.monday == 1
                    2 -> cal.tuesday == 1
                    3 -> cal.wednesday == 1
                    4 -> cal.thursday == 1
                    5 -> cal.friday == 1
                    6 -> cal.saturday == 1
                    7 -> cal.sunday == 1
                    else -> false
                }
            }
            if (!isActive) continue
            val stopTime = stopTimeEntries.firstOrNull { it.tripId == tripId } ?: continue
            val arrivalTimeStr = stopTime.arrivalTime ?: stopTime.departureTime ?: continue
            val scheduledSeconds = parseGtfsTimeToSeconds(arrivalTimeStr) ?: continue
            val serviceDayStart = getServiceDayStartEpoch(agencyTz, scheduledSeconds)
            val predictedEpoch = serviceDayStart + scheduledSeconds
            if (predictedEpoch < nowEpoch - 60) continue
            scheduledOnly.add(
                ArrivalPrediction(
                    feedId = feedId,
                    tripId = tripId,
                    routeId = trip.routeId,
                    stopId = stopTime.stopId,
                    stopSequence = stopTime.stopSequence,
                    scheduledArrivalSeconds = scheduledSeconds,
                    predictedArrivalEpoch = predictedEpoch,
                    delaySeconds = 0,
                    isSkipped = false,
                    isCanceled = false,
                    feedTimestamp = null,
                    tripUpdateTimestamp = null,
                    agencyTimezone = agencyTz,
                    isRealtime = false
                )
            )
        }

        val allPredictions = predictions.filter { it.stopId in effectiveIds } + scheduledOnly

        allPredictions
            // 過去の便を除外（予測到着時刻が現在より前のものは表示しない）
            .filter { p ->
                val pred = p.predictedArrivalEpoch ?: return@filter true
                pred >= nowEpoch - 60 // 1分前までは許容（更新タイミングのズレ対策）
            }
            .map { p ->
                val trip = tripMap[p.tripId]
                val route = trip?.let { routeMap[it.routeId] }
                val stop = allStops.firstOrNull { it.stopId == p.stopId }
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