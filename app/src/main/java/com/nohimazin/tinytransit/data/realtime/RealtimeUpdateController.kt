package com.nohimazin.tinytransit.data.realtime

import android.content.Context
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 定期取得の制御を行うコントローラー。
 * - 取得間隔の管理
 * - 重複リクエスト防止
 * - 通信失敗時も通常周期で再取得
 * - ネットワーク監視
 * - 通信量・更新時刻の記録
 */
class RealtimeUpdateController(
    private val context: Context,
    private val database: TinyTransitDatabase,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "RealtimeCtrl"
        const val DEFAULT_INTERVAL_MS = 15_000L
        const val INTERVAL_HIGH = 15_000L
        const val INTERVAL_NORMAL = 30_000L
        const val INTERVAL_SAVE = 60_000L
    }

    // 設定
    @Volatile
    var intervalMs: Long = DEFAULT_INTERVAL_MS

    // 状態
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _lastUpdateEpoch = MutableStateFlow(0L)
    val lastUpdateEpoch: StateFlow<Long> = _lastUpdateEpoch

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes

    private val _lastDurationMs = MutableStateFlow(0L)
    val lastDurationMs: StateFlow<Long> = _lastDurationMs

    // 内部状態
    private var monitorJob: Job? = null
    private val isFetching = AtomicBoolean(false)
    private var feedId: String = GtfsRepository.DEFAULT_FEED_ID
    private var stopId: String? = null
    private var tripUpdateUrl: String = "https://km.bus-vision.jp/realtime/sankobus_trip_update.bin"

    // 最新の到着予測
    private val _latestPredictions = MutableStateFlow<List<ArrivalPrediction>>(emptyList())
    val latestPredictions: StateFlow<List<ArrivalPrediction>> = _latestPredictions

    // ネットワーク監視
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var isNetworkAvailable = true

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
            if (!isNetworkAvailable) {
                delay(5000)
                continue
            }

            // 前回のリクエストが完了するまで待機（重複防止）
            if (isFetching.get()) {
                delay(500)
                continue
            }

            val startTime = SystemClock.elapsedRealtime()
            isFetching.set(true)
            try {
                val predictions = repository.fetchAndDecodeTripUpdates(feedId, tripUpdateUrl)
                _latestPredictions.value = predictions
                _lastUpdateEpoch.value = System.currentTimeMillis()
                _lastError.value = null
                _totalBytes.value += 0 // placeholder; actual bytes tracked in repository
            } catch (e: Exception) {
                Log.w(TAG, "Fetch failed: ${e.message}")
                _lastError.value = e.message
            } finally {
                isFetching.set(false)
                val duration = SystemClock.elapsedRealtime() - startTime
                _lastDurationMs.value = duration
            }

            // 次の取得タイミングを計算
            // 開始時刻基準で intervalMs 待つ。取得に時間がかかった場合は自然に延長。
            val elapsed = SystemClock.elapsedRealtime() - startTime
            val waitTime = (intervalMs - elapsed).coerceAtLeast(0)
            if (waitTime > 0) {
                delay(waitTime)
            }
        }
    }

    private fun startNetworkMonitoring() {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = connectivityManager?.getNetworkCapabilities(network)
                isNetworkAvailable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                if (isNetworkAvailable) {
                    Log.d(TAG, "Network available")
                }
            }

            override fun onLost(network: Network) {
                isNetworkAvailable = false
                Log.d(TAG, "Network lost")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                isNetworkAvailable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
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