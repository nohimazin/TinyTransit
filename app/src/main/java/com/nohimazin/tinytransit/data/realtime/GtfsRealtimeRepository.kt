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

    /**
     * 静的 GTFS の時刻表から、指定停留所の今後の到着予定を取得します。
     * GTFS-RT のデータがない場合のフォールバックとして使用します。
     */
    suspend fun getStaticArrivalsForStop(
        feedId: String,
        originStopId: String,
        destStopId: String?
    ): List<StopArrival> = withContext(Dispatchers.IO) {
        val baseId = GtfsRepository.baseStopId(originStopId)
        val allStops = database.stopDao().getAll(feedId)
        val variantIds = allStops.filter { GtfsRepository.baseStopId(it.stopId) == baseId }
            .map { it.stopId }.toSet()
        val effectiveIds = if (variantIds.isEmpty()) setOf(originStopId) else variantIds

        // 乗車停留所の stop_times を取得
        val allStopTimes = effectiveIds.flatMap { v ->
            database.stopTimeDao().getByStop(feedId, v)
        }

        // 降車停留所を経由する便をフィルター (方向を考慮)
        val commonTrips = if (destStopId != null) {
            val destBase = GtfsRepository.baseStopId(destStopId)
            val destVariants = allStops.filter { GtfsRepository.baseStopId(it.stopId) == destBase }
                .map { it.stopId }.toSet()
            val destStopTimes = destVariants.flatMap { v ->
                database.stopTimeDao().getByStop(feedId, v)
            }
            // 乗車 → 降車の順で停車する便のみ
            // 同一 trip の origin と dest の stop_sequence ペアを確認
            val originByTrip = allStopTimes.groupBy { it.tripId }
            destStopTimes.filter { dst ->
                val originSts = originByTrip[dst.tripId] ?: return@filter false
                // origin のいずれかの stop_sequence が dest より小さいものがあれば forward
                originSts.any { originSt -> originSt.stopSequence < dst.stopSequence }
            }.map { it.tripId }.toSet()
        } else null

        // trip ごとにグループ化
        val tripsById = allStopTimes.groupBy { it.tripId }

        // 事業者のタイムゾーン
        val agencyTz = database.feedDao().get(feedId)?.agencyTimezone ?: "Asia/Tokyo"
        val tz = java.util.TimeZone.getTimeZone(agencyTz)

        // 現在時刻
        val now = System.currentTimeMillis()
        val nowCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = now }
        val todayMidnight = java.util.Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        // サービス日を確認 (calendar)
        val serviceIds = tripsById.keys.mapNotNull { tid ->
            database.tripDao().get(feedId, tid)?.serviceId
        }.distinct()

        // 今日のサービス ID を確認
        val todayStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).apply {
            timeZone = tz
        }.format(java.util.Date(now))
        val activeServiceIds = serviceIds.filter { sid ->
            val cal = database.calendarDao().getCalendar(feedId, sid)
            if (cal != null) {
                // calendar.txt の曜日チェック
                val dayOfWeek = nowCal.get(java.util.Calendar.DAY_OF_WEEK)
                val dayFlag = when (dayOfWeek) {
                    java.util.Calendar.MONDAY -> cal.monday
                    java.util.Calendar.TUESDAY -> cal.tuesday
                    java.util.Calendar.WEDNESDAY -> cal.wednesday
                    java.util.Calendar.THURSDAY -> cal.thursday
                    java.util.Calendar.FRIDAY -> cal.friday
                    java.util.Calendar.SATURDAY -> cal.saturday
                    java.util.Calendar.SUNDAY -> cal.sunday
                    else -> 0
                }
                if (dayFlag != 1) return@filter false
                // 期間チェック
                if (todayStr < cal.startDate || todayStr > cal.endDate) return@filter false
                true
            } else {
                // calendar_dates で確認
                val dates = database.calendarDao().getCalendarDates(feedId, sid)
                val exception = dates.firstOrNull { it.date == todayStr }
                when (exception?.exceptionType) {
                    1 -> true  // 追加
                    2 -> false // 除外
                    else -> false
                }
            }
        }.toSet()

        val result = mutableListOf<StopArrival>()
        for ((tripId, stopTimeList) in tripsById) {
            val trip = database.tripDao().get(feedId, tripId) ?: continue
            if (trip.serviceId !in activeServiceIds) continue
            if (commonTrips != null && tripId !in commonTrips) continue

            val route = database.routeDao().get(feedId, trip.routeId)
            val st = stopTimeList.firstOrNull() ?: continue
            val timeStr = st.arrivalTime ?: st.departureTime ?: continue
            val seconds = parseGtfsTimeToSeconds(timeStr) ?: continue

            // GTFS 時刻をエポックに変換 (24h+ 対応)
            var epochSeconds = todayMidnight / 1000 + seconds
            // 時刻が過去の場合は翌日として扱う (24h+ でない場合)
            if (epochSeconds < now / 1000) {
                // 24h を超える時刻 (例: 25:10:00) の場合はそのまま
                if (seconds < 86400) {
                    epochSeconds += 86400 // 翌日
                }
            }
            // 過去の便は除外 (5分前までは許容)
            if (epochSeconds < now / 1000 - 300) continue

            val stop = allStops.firstOrNull { it.stopId == st.stopId }
            result.add(
                StopArrival(
                    prediction = ArrivalPrediction(
                        feedId = feedId,
                        tripId = tripId,
                        routeId = trip.routeId,
                        stopId = st.stopId,
                        stopSequence = st.stopSequence,
                        scheduledArrivalSeconds = seconds,
                        predictedArrivalEpoch = epochSeconds,
                        delaySeconds = null,
                        isSkipped = false,
                        isCanceled = false,
                        feedTimestamp = null,
                        isRealtime = false
                    ),
                    stopName = stop?.stopName,
                    routeShortName = route?.routeShortName,
                    routeLongName = route?.routeLongName,
                    tripHeadsign = trip.tripHeadsign
                )
            )
        }

        result.sortedBy { it.prediction.effectivePredictedEpoch(0) ?: Long.MAX_VALUE }
            .take(10)
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
        predictions.filter { it.stopId in effectiveIds }
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