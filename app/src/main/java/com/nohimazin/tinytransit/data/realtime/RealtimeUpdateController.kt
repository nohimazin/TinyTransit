package com.nohimazin.tinytransit.data.realtime

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.nohimazin.tinytransit.data.db.TinyTransitDatabase
import com.nohimazin.tinytransit.data.repository.GtfsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 定期取得の制御を行うコントローラー。
 * - 取得間隔の管理 (SharedPreferences で永続化)
 * - 重複リクエスト防止 (Single-flight は Repository 側)
 * - ネットワーク監視 (種別 + 検証状態)
 * - 通信失敗時も通常周期で再取得 (指数バックオフなし)
 * - Retry-After の尊重 (429/503)
 * - 通信量の実測
 */
class RealtimeUpdateController(
    private val context: Context,
    private val database: TinyTransitDatabase,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "RealtimeCtrl"
        private const val PREFS_NAME = "realtime_settings"
        private const val KEY_INTERVAL_MS = "interval_ms"
        private const val KEY_SHOW_DOWNLOAD_PROGRESS = "show_download_progress"
        private const val KEY_PAUSE_WHEN_NO_ACTIVE = "pause_when_no_active"

        fun isShowDownloadProgress(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_DOWNLOAD_PROGRESS, true)
        }

        fun setShowDownloadProgress(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SHOW_DOWNLOAD_PROGRESS, value).apply()
        }

        fun isPauseWhenNoActive(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PAUSE_WHEN_NO_ACTIVE, false)
        }

        fun setPauseWhenNoActive(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PAUSE_WHEN_NO_ACTIVE, value).apply()
        }

        const val INTERVAL_HIGH = 15_000L
        const val INTERVAL_NORMAL = 30_000L
        const val INTERVAL_SAVE = 60_000L
        val INTERVAL_OPTIONS = listOf(INTERVAL_HIGH, INTERVAL_NORMAL, INTERVAL_SAVE)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 設定 (SharedPreferences から復元)
    @Volatile
    var intervalMs: Long = prefs.getLong(KEY_INTERVAL_MS, INTERVAL_HIGH)
        set(value) {
            field = value
            prefs.edit().putLong(KEY_INTERVAL_MS, value).apply()
        }

    @Volatile
    var showDownloadProgress: Boolean = prefs.getBoolean(KEY_SHOW_DOWNLOAD_PROGRESS, true)
        set(value) {
            field = value
            prefs.edit().putBoolean(KEY_SHOW_DOWNLOAD_PROGRESS, value).apply()
        }

    @Volatile
    var pauseWhenNoActiveBuses: Boolean = prefs.getBoolean(KEY_PAUSE_WHEN_NO_ACTIVE, false)
        set(value) {
            field = value
            prefs.edit().putBoolean(KEY_PAUSE_WHEN_NO_ACTIVE, value).apply()
        }

    // 状態
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _lastUpdateEpoch = MutableStateFlow(0L)
    val lastUpdateEpoch: StateFlow<Long> = _lastUpdateEpoch

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _lastErrorType = MutableStateFlow(FetchErrorType.NONE)
    val lastErrorType: StateFlow<FetchErrorType> = _lastErrorType

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes

    private val _lastDurationMs = MutableStateFlow(0L)
    val lastDurationMs: StateFlow<Long> = _lastDurationMs

    private val _networkState = MutableStateFlow(NetworkState(NetworkType.OFFLINE, false))
    val networkState: StateFlow<NetworkState> = _networkState

    private val _latestPredictions = MutableStateFlow<List<ArrivalPrediction>>(emptyList())
    val latestPredictions: StateFlow<List<ArrivalPrediction>> = _latestPredictions

    // 待機モード (運行時間外) の状態
    private val _isWaiting = MutableStateFlow(false)
    val isWaiting: StateFlow<Boolean> = _isWaiting

    // 待機モードからの再開予定時刻 (epoch ms)。0 = 未設定
    private val _nextResumeEpochMs = MutableStateFlow(0L)
    val nextResumeEpochMs: StateFlow<Long> = _nextResumeEpochMs

    // ダウンロード進捗 (downloaded, total) or null
    private val _downloadProgress = MutableStateFlow<Pair<Long, Long>?>(null)
    val downloadProgress: StateFlow<Pair<Long, Long>?> = _downloadProgress

    // 内部状態
    private var monitorJob: Job? = null
    private var feedId: String = GtfsRepository.DEFAULT_FEED_ID
    private var stopId: String? = null
    private var tripUpdateUrl: String = "https://km.bus-vision.jp/realtime/sankobus_trip_update.bin"

    // 監視対象の停留所 ID (複数可, 乗車停留所)
    private val monitoredStopIds = mutableSetOf<String>()

    fun setMonitoredStops(stopIds: Set<String>) {
        monitoredStopIds.clear()
        monitoredStopIds.addAll(stopIds)
    }

    fun monitoredStopIds(): Set<String> = monitoredStopIds.toSet()

    // 監視対象の経路 (乗車停留所 -> 降車停留所)
    private val monitoredRoutes = mutableSetOf<Pair<String, String>>()

    fun setMonitoredRoutes(routes: Set<Pair<String, String>>) {
        monitoredRoutes.clear()
        monitoredRoutes.addAll(routes)
    }

    fun monitoredRoutes(): Set<Pair<String, String>> = monitoredRoutes.toSet()

    // Retry-After 期限 (elapsedRealtime ベース)
    @Volatile
    private var retryAfterDeadline: Long = 0L

    // ネットワーク監視
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val repository by lazy {
        GtfsRealtimeRepository(context, database, httpClient)
    }

    /**
     * 静的 GTFS の時刻表から到着予定を取得します (フォールバック用)。
     */
    suspend fun getStaticArrivals(feedId: String, originStopId: String, destStopId: String?): List<StopArrival> {
        return repository.getStaticArrivalsForStop(feedId, originStopId, destStopId)
    }

    fun configure(feedId: String, stopId: String?, tripUpdateUrl: String) {
        this.feedId = feedId
        this.stopId = stopId
        this.tripUpdateUrl = tripUpdateUrl
    }

    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true
        _lastError.value = null
        _lastErrorType.value = FetchErrorType.NONE
        startNetworkMonitoring()
        monitorJob = scope.launch {
            runLoop()
        }
    }

    fun stop() {
        _isRunning.value = false
        _isWaiting.value = false
        _nextResumeEpochMs.value = 0L
        monitorJob?.cancel()
        monitorJob = null
        stopNetworkMonitoring()
    }

    private suspend fun runLoop() {
        while (scope.isActive && _isRunning.value) {
            // ネットワークが利用不可の場合は待機
            val netState = _networkState.value
            if (!netState.isValidated) {
                delay(5000)
                continue
            }

            // 運行時間外チェック: 取得前に判定し、通信自体を回避します。
            // キャッシュされた予測が空 (初回) の場合は通常取得を行います。
            val pauseEnabled = isPauseWhenNoActive(context)
            if (pauseEnabled) {
                val monitored = monitoredStopIds()
                val cached = _latestPredictions.value
                if (cached.isNotEmpty()) {
                    val nowEpoch = System.currentTimeMillis() / 1000
                    val nearFutureSec = 2 * 60 * 60L
                    val recentPastSec = 30 * 60L
                    val routes = monitoredRoutes()
                    val hasActiveBus = if (routes.isNotEmpty()) {
                        // 経路 (乗車->降車) を考慮: 降車停留所を経由する便のみを「走行中」とみなす
                        routes.any { (origin, dest) ->
                            val common = commonTripsForRoute(origin, dest)
                            cached.any { pred ->
                                if (GtfsRepository.baseStopId(pred.stopId) != GtfsRepository.baseStopId(origin)) return@any false
                                if (common != null && common.isNotEmpty() && pred.tripId !in common) return@any false
                                val pa = pred.predictedArrivalEpoch
                                if (pa == null) return@any true
                                pa in (nowEpoch - recentPastSec)..(nowEpoch + nearFutureSec)
                            }
                        }
                    } else {
                        // 経路情報がない場合は乗車停留所ベース (後方互換)
                        monitored.any { mid ->
                            cached.any { pred ->
                                if (GtfsRepository.baseStopId(pred.stopId) != GtfsRepository.baseStopId(mid)) return@any false
                                val pa = pred.predictedArrivalEpoch
                                if (pa == null) return@any true
                                pa in (nowEpoch - recentPastSec)..(nowEpoch + nearFutureSec)
                            }
                        }
                    }
                    if (!hasActiveBus) {
                        val nextDeparture = findNextDepartureEpoch()
                        if (nextDeparture != null) {
                            val msUntilDeparture = nextDeparture - System.currentTimeMillis()
                            // 次の便の出発まで 5 分以上ある場合のみ一時停止し、
                            // 出発 5 分前から取得を再開する。
                            // 待機中は isWaiting=true を維持し、通知のちらつきを防ぐ。
                            if (msUntilDeparture > 5 * 60 * 1000L) {
                                val resumeAt = nextDeparture - 5 * 60 * 1000L
                                if (!_isWaiting.value) {
                                    _isWaiting.value = true
                                }
                                _nextResumeEpochMs.value = resumeAt
                                // 最大 5 分ごとに再確認 (境界での再待機ループを防ぐ)
                                val remaining = resumeAt - System.currentTimeMillis()
                                val waitMs = if (msUntilDeparture > 10 * 60 * 1000L) {
                                    remaining.coerceIn(1_000L, 30 * 60 * 1000L)
                                } else {
                                    remaining.coerceIn(1_000L, 60 * 1000L)
                                }
                                Log.w(TAG, "Pause: no active bus, waiting ${waitMs}ms (nextDeparture=$nextDeparture, msUntil=$msUntilDeparture)")
                                if (waitMs > 0) {
                                    delay(waitMs)
                                }
                                continue
                            } else {
                                // 5 分以内: 待機解除して通常取得
                                if (_isWaiting.value) {
                                    _isWaiting.value = false
                                    _nextResumeEpochMs.value = 0L
                                }
                            }
                        } else {
                            Log.w(TAG, "Pause: no active bus, nextDeparture unknown, waiting 30min")
                            if (!_isWaiting.value) _isWaiting.value = true
                            _nextResumeEpochMs.value = System.currentTimeMillis() + 30 * 60 * 1000L
                            delay(30 * 60 * 1000L)
                            continue
                        }
                    } else {
                        // 走行中のバスあり: 待機解除
                        if (_isWaiting.value) {
                            _isWaiting.value = false
                            _nextResumeEpochMs.value = 0L
                        }
                    }
                }
            }

            val startTime = SystemClock.elapsedRealtime()
            _downloadProgress.value = 0L to 0L
            var result: FetchResult? = null
            try {
                result = repository.fetch(feedId, tripUpdateUrl) { downloaded, total ->
                    _downloadProgress.value = downloaded to total
                }
                when (result) {
                    is FetchResult.Success -> {
                        _latestPredictions.value = result.predictions
                        _lastUpdateEpoch.value = System.currentTimeMillis()
                        _lastError.value = null
                        _lastErrorType.value = FetchErrorType.NONE
                        _totalBytes.value += result.receivedBytes
                    }
                    is FetchResult.HttpError -> {
                        _lastError.value = "HTTP ${result.statusCode}"
                        _lastErrorType.value = FetchErrorType.HTTP_ERROR
                        _totalBytes.value += result.receivedBytes
                        // Retry-After の処理
                        result.retryAfterMs?.let { ms ->
                            val deadline = SystemClock.elapsedRealtime() + ms
                            if (deadline > retryAfterDeadline) {
                                retryAfterDeadline = deadline
                            }
                        }
                    }
                    is FetchResult.NetworkError -> {
                        _lastError.value = result.exception.message
                        _lastErrorType.value = FetchErrorType.NETWORK_ERROR
                    }
                    is FetchResult.Timeout -> {
                        _lastError.value = "Timeout"
                        _lastErrorType.value = FetchErrorType.TIMEOUT
                    }
                    is FetchResult.DecodeError -> {
                        _lastError.value = "Decode error: ${result.exception.message}"
                        _lastErrorType.value = FetchErrorType.DECODE_ERROR
                        _totalBytes.value += result.receivedBytes
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected: ${e.message}")
                _lastError.value = e.message
                _lastErrorType.value = FetchErrorType.NETWORK_ERROR
            } finally {
                _downloadProgress.value = null
                val duration = SystemClock.elapsedRealtime() - startTime
                _lastDurationMs.value = duration
            }

            // 次の取得タイミングを計算
            // 通常周期と Retry-After のうち、遅い方を採用
            val elapsed = SystemClock.elapsedRealtime() - startTime
            val normalWait = (intervalMs - elapsed).coerceAtLeast(0)
            val retryWait = (retryAfterDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            var waitTime = maxOf(normalWait, retryWait)

            // (運行時間外チェックは取得前に実施済み)

            if (waitTime > 0) {
                delay(waitTime)
            }
        }
    }

    private fun startNetworkMonitoring() {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetworkState(network)
            }

            override fun onLost(network: Network) {
                // デフォルトネットワークが失われた場合のみオフライン
                val defaultNetwork = connectivityManager?.activeNetwork
                if (defaultNetwork == null || defaultNetwork == network) {
                    _networkState.value = NetworkState(NetworkType.OFFLINE, false)
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                updateNetworkState(network)
            }
        }
        networkCallback = callback

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(request, callback)
            }
            // 初期状態を設定
            updateCurrentNetworkState()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun updateCurrentNetworkState() {
        val cm = connectivityManager ?: return
        val network = cm.activeNetwork
        if (network == null) {
            _networkState.value = NetworkState(NetworkType.OFFLINE, false)
            return
        }
        updateNetworkState(network)
    }

    private fun updateNetworkState(network: Network) {
        val cm = connectivityManager ?: return
        val caps = cm.getNetworkCapabilities(network) ?: return
        // デフォルトネットワークか確認 (API 23 対応)
        val defaultNetwork = cm.activeNetwork
        if (defaultNetwork != null && defaultNetwork != network && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // API 23: registerNetworkCallback はすべてのネットワークを通知するため、
            // デフォルトネットワーク以外は無視
            return
        }

        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.MOBILE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            else -> NetworkType.OTHER
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        _networkState.value = NetworkState(type, validated && hasInternet)
    }

    private fun stopNetworkMonitoring() {
        networkCallback?.let { cb ->
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister: ${e.message}")
            }
        }
        networkCallback = null
        connectivityManager = null
    }

    /**
     * 監視対象の停留所の次の出発時刻 (epoch ms) を取得します。
     * 静的 GTFS の stop_times と calendar から計算します。
     */
    /**
     * 指定経路 (乗車 -> 降車) を経由する便の tripId セットを返します。
     * 降車 == 乗車 の場合は null (フィルタなし)。
     */
    private suspend fun commonTripsForRoute(originStopId: String, destStopId: String): Set<String>? {
        if (GtfsRepository.baseStopId(originStopId) == GtfsRepository.baseStopId(destStopId)) return null
        val baseId = GtfsRepository.baseStopId(originStopId)
        val destBase = GtfsRepository.baseStopId(destStopId)
        val allStops = database.stopDao().getAll(feedId)
        val originVariants = allStops.filter { GtfsRepository.baseStopId(it.stopId) == baseId }.map { it.stopId }.toSet()
        val destVariants = allStops.filter { GtfsRepository.baseStopId(it.stopId) == destBase }.map { it.stopId }.toSet()
        val effectiveOrigin = if (originVariants.isEmpty()) setOf(originStopId) else originVariants
        val effectiveDest = if (destVariants.isEmpty()) setOf(destStopId) else destVariants
        val originStopTimes = effectiveOrigin.flatMap { database.stopTimeDao().getByStop(feedId, it) }
        val destStopTimes = effectiveDest.flatMap { database.stopTimeDao().getByStop(feedId, it) }
        val originByTrip = originStopTimes.groupBy { it.tripId }
        return destStopTimes.filter { dst ->
            val originSts = originByTrip[dst.tripId] ?: return@filter false
            originSts.any { it.stopSequence < dst.stopSequence }
        }.map { it.tripId }.toSet()
    }

    private suspend fun findNextDepartureEpoch(): Long? {
        if (monitoredStopIds.isEmpty()) return null
        val tz = java.util.TimeZone.getTimeZone("Asia/Tokyo")
        val now = System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).apply {
            timeZone = tz
        }

        var nextDeparture: Long? = null
        var bestMonitoredDepEpoch: Long = Long.MAX_VALUE

        // 今日から 7 日先まで走査して、最初の運行日・便の出発時刻を求めます
        for (dayOffset in 0..6) {
            val dayCal = java.util.Calendar.getInstance(tz).apply {
                timeInMillis = now
                add(java.util.Calendar.DAY_OF_MONTH, dayOffset)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val dayStr = sdf.format(java.util.Date(dayCal.timeInMillis))
            val midnight = dayCal.timeInMillis

            for (stopId in monitoredStopIds) {
                val baseId = GtfsRepository.baseStopId(stopId)
                val allStops = database.stopDao().getAll(feedId)
                val variants = allStops.filter { GtfsRepository.baseStopId(it.stopId) == baseId }
                val stopTimes = variants.flatMap { v ->
                    database.stopTimeDao().getByStop(feedId, v.stopId)
                }

                for (st in stopTimes) {
                    val trip = database.tripDao().get(feedId, st.tripId) ?: continue
                    val cal = database.calendarDao().getCalendar(feedId, trip.serviceId)
                    // サービス日チェック
                    val isActive = if (cal != null) {
                        val dayOfWeek = dayCal.get(java.util.Calendar.DAY_OF_WEEK)
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
                        dayFlag == 1 && dayStr >= cal.startDate && dayStr <= cal.endDate
                    } else {
                        val dates = database.calendarDao().getCalendarDates(feedId, trip.serviceId)
                        dates.any { it.date == dayStr && it.exceptionType == 1 }
                    }
                    if (!isActive) continue

                    // 監視停留所の出発時刻 (便がこの停留所を通過する時刻)
                    val monitoredDepTime = st.departureTime ?: st.arrivalTime ?: continue
                    val mParts = monitoredDepTime.split(":")
                    if (mParts.size != 3) continue
                    val mh = mParts[0].toLongOrNull() ?: continue
                    val mm = mParts[1].toLongOrNull() ?: continue
                    val ms = mParts[2].toLongOrNull() ?: continue
                    val monitoredSeconds = mh * 3600 + mm * 60 + ms

                    var monitoredDepEpoch = midnight + monitoredSeconds * 1000
                    if (monitoredSeconds >= 86400) {
                        monitoredDepEpoch = midnight + (monitoredSeconds - 86400) * 1000 + 86400 * 1000
                    }
                    // 監視停留所を通過済みの便は対象外だが、遅延を考慮して
                    // 30 分以内に予定されていた便は「まだ到着していない可能性」として含める
                    if (monitoredDepEpoch < now - 30 * 60 * 1000L) continue

                    // 便の起点 (最初の停留所) の出発時刻を取得
                    // → 再開タイミングは「起点バス停を出発する 5 分前」基準
                    val tripStopTimes = database.stopTimeDao().getByTrip(feedId, st.tripId)
                    val firstStop = tripStopTimes.minByOrNull { it.stopSequence }
                    val startDepTime = firstStop?.departureTime ?: firstStop?.arrivalTime ?: monitoredDepTime
                    val sParts = startDepTime.split(":")
                    if (sParts.size != 3) continue
                    val sh = sParts[0].toLongOrNull() ?: continue
                    val sm = sParts[1].toLongOrNull() ?: continue
                    val ss = sParts[2].toLongOrNull() ?: continue
                    val startSeconds = sh * 3600 + sm * 60 + ss

                    var startDepEpoch = midnight + startSeconds * 1000
                    if (startSeconds >= 86400) {
                        startDepEpoch = midnight + (startSeconds - 86400) * 1000 + 86400 * 1000
                    }

                    // 最も近い monitored 出発の便を選び、その起点出発時刻を返す
                    if (nextDeparture == null || monitoredDepEpoch < bestMonitoredDepEpoch) {
                        bestMonitoredDepEpoch = monitoredDepEpoch
                        nextDeparture = startDepEpoch
                    }
                }
            }
        }

        return nextDeparture
    }
}

enum class NetworkType {
    WIFI, MOBILE, ETHERNET, VPN, OTHER, OFFLINE
}

data class NetworkState(
    val type: NetworkType,
    val isValidated: Boolean
)