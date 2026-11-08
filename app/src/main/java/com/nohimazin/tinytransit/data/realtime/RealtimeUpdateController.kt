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

    // 内部状態
    private var monitorJob: Job? = null
    private var feedId: String = GtfsRepository.DEFAULT_FEED_ID
    private var stopId: String? = null
    private var tripUpdateUrl: String = "https://km.bus-vision.jp/realtime/sankobus_trip_update.bin"

    // Retry-After 期限 (elapsedRealtime ベース)
    @Volatile
    private var retryAfterDeadline: Long = 0L

    // ネットワーク監視
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val repository by lazy {
        GtfsRealtimeRepository(context, database, httpClient)
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

            val startTime = SystemClock.elapsedRealtime()
            try {
                val result = repository.fetch(feedId, tripUpdateUrl)
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
                val duration = SystemClock.elapsedRealtime() - startTime
                _lastDurationMs.value = duration
            }

            // 次の取得タイミングを計算
            // 通常周期と Retry-After のうち、遅い方を採用
            val elapsed = SystemClock.elapsedRealtime() - startTime
            val normalWait = (intervalMs - elapsed).coerceAtLeast(0)
            val retryWait = (retryAfterDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            val waitTime = maxOf(normalWait, retryWait)
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
}

enum class NetworkType {
    WIFI, MOBILE, ETHERNET, VPN, OTHER, OFFLINE
}

data class NetworkState(
    val type: NetworkType,
    val isValidated: Boolean
)