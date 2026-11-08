package com.nohimazin.tinytransit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nohimazin.tinytransit.MainActivity
import com.nohimazin.tinytransit.R
import com.nohimazin.tinytransit.TinyTransitApp
import com.nohimazin.tinytransit.data.realtime.ArrivalPrediction
import com.nohimazin.tinytransit.data.realtime.RealtimeUpdateController
import com.nohimazin.tinytransit.data.repository.GtfsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RealtimeMonitorService : Service() {

    companion object {
        private const val TAG = "RealtimeMonitor"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "realtime_monitor"
        const val ACTION_STOP = "com.nohimazin.tinytransit.ACTION_STOP_MONITOR"
        const val ACTION_UPDATE_SELECTION = "com.nohimazin.tinytransit.ACTION_UPDATE_SELECTION"
        const val EXTRA_FEED_ID = "feed_id"
        const val EXTRA_STOP_ID = "stop_id"
        const val EXTRA_STOP_NAME = "stop_name"
        const val EXTRA_SELECTED_TRIP_ID = "selected_trip_id"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentStopId: String? = null
            private set

        @Volatile
        var currentStopName: String? = null
            private set

        @Volatile
        var currentSelectedTripId: String? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var controller: RealtimeUpdateController? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 停止アクション
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_UPDATE_SELECTION) {
            val newTripId = intent.getStringExtra(EXTRA_SELECTED_TRIP_ID)
            currentSelectedTripId = newTripId
            controller?.let { ctrl ->
                serviceScope.launch {
                    val predictions = ctrl.latestPredictions.value
                    val stopId = currentStopId
                    val nowEpoch = System.currentTimeMillis() / 1000
                    var filtered = if (stopId != null) {
                        val baseId = GtfsRepository.baseStopId(stopId)
                        predictions.filter { GtfsRepository.baseStopId(it.stopId) == baseId }
                    } else {
                        predictions
                    }
                    filtered = filtered.filter { p ->
                        val pred = p.predictedArrivalEpoch ?: return@filter true
                        pred >= nowEpoch - 60
                    }
                    if (newTripId != null) {
                        filtered = filtered.filter { it.tripId == newTripId }
                    }
                    updateNotification(currentStopName, filtered, ctrl.lastUpdateEpoch.value)
                }
            }
            return START_STICKY
        }

        val feedId = intent?.getStringExtra(EXTRA_FEED_ID) ?: GtfsRepository.DEFAULT_FEED_ID
        val stopId = intent?.getStringExtra(EXTRA_STOP_ID)
        val stopName = intent?.getStringExtra(EXTRA_STOP_NAME)
        val selectedTripId = intent?.getStringExtra(EXTRA_SELECTED_TRIP_ID)

        currentStopId = stopId
        currentStopName = stopName
        currentSelectedTripId = selectedTripId

        // 速やかに startForeground を実行
        val notification = buildNotification(stopName, emptyList(), null)
        startForeground(NOTIFICATION_ID, notification)

        // 監視開始
        startMonitoring(feedId, stopId, selectedTripId)

        isRunning = true
        return START_STICKY
    }

    private fun startMonitoring(feedId: String, stopId: String?, selectedTripId: String?) {
        val app = application as TinyTransitApp
        controller = RealtimeUpdateController(
            context = this,
            database = app.database,
            httpClient = app.realtimeHttpClient,
            scope = serviceScope
        ).apply {
            configure(
                feedId = feedId,
                stopId = stopId,
                tripUpdateUrl = "https://km.bus-vision.jp/realtime/sankobus_trip_update.bin"
            )
            start()
        }

        // 予測更新を監視して通知を更新
        serviceScope.launch {
            controller?.latestPredictions?.collectLatest { predictions ->
                val nowEpoch = System.currentTimeMillis() / 1000
                var filtered = if (stopId != null) {
                    val baseId = GtfsRepository.baseStopId(stopId)
                    predictions.filter { GtfsRepository.baseStopId(it.stopId) == baseId }
                } else {
                    predictions
                }
                // 過去の便を除外
                filtered = filtered.filter { p ->
                    val pred = p.predictedArrivalEpoch ?: return@filter true
                    pred >= nowEpoch - 60
                }
                // 選択された便がある場合はそれのみ表示
                val selTrip = currentSelectedTripId
                if (selTrip != null) {
                    filtered = filtered.filter { it.tripId == selTrip }
                }
                updateNotification(currentStopName, filtered, controller?.lastUpdateEpoch?.value)
            }
        }
    }

    private fun stopMonitoring() {
        controller?.stop()
        controller = null
        isRunning = false
        currentStopId = null
        currentStopName = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ の実行時間制限に対応
        Log.w(TAG, "Service timeout reached, stopping")
        stopMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        isRunning = false
        controller = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- 通知 ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "バックグラウンド監視",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "TinyTransit の到着予測をバックグラウンドで更新します"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        stopName: String?,
        predictions: List<ArrivalPrediction>,
        lastUpdateEpoch: Long?
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RealtimeMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = buildNotificationTitle(stopName, predictions, lastUpdateEpoch)
        val content = buildNotificationContent(stopName, predictions, lastUpdateEpoch)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildNotificationTitle(
        stopName: String?,
        predictions: List<ArrivalPrediction>,
        lastUpdateEpoch: Long?
    ): String {
        if (predictions.isEmpty()) return "到着予測なし"
        val nearest = predictions.minByOrNull { it.predictedArrivalEpoch ?: Long.MAX_VALUE } ?: return "到着予測なし"
        val mins = nearest.minutesUntil(System.currentTimeMillis() / 1000)
        val minsText = if (mins != null) "あと${mins}分" else "到着済み"
        val delay = nearest.delayText()
        val ago = if (lastUpdateEpoch != null && lastUpdateEpoch > 0) {
            val sec = (System.currentTimeMillis() - lastUpdateEpoch) / 1000
            "${sec}秒前取得"
        } else ""
        return "$minsText・$delay・$ago".trim('・', ' ')
    }

    private fun buildNotificationContent(
        stopName: String?,
        predictions: List<ArrivalPrediction>,
        lastUpdateEpoch: Long?
    ): String {
        val sb = StringBuilder()
        if (stopName != null) {
            sb.append("停留所: $stopName\n")
        }
        if (predictions.isEmpty()) {
            sb.append("到着予測なし")
        } else {
            val nearest = predictions.minByOrNull { it.predictedArrivalEpoch ?: Long.MAX_VALUE }
            if (nearest != null) {
                // 路線名を DB から取得
                val app = application as TinyTransitApp
                val route = nearest.routeId?.let { rid ->
                    kotlinx.coroutines.runBlocking {
                        app.database.routeDao().get(nearest.feedId, rid)
                    }
                }
                val routeName = route?.routeLongName ?: route?.routeShortName ?: nearest.routeId ?: ""
                val time = nearest.predictedTimeText()
                val delay = nearest.delayText()
                val mins = nearest.minutesUntil(System.currentTimeMillis() / 1000)
                val minsText = if (mins != null) "あと${mins}分" else ""
                sb.append("$routeName - $time ($delay) $minsText")
            }
        }
        if (lastUpdateEpoch != null && lastUpdateEpoch > 0) {
            val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(
                java.util.Date(lastUpdateEpoch)
            )
            sb.append("\n更新: $timeStr")
        }
        return sb.toString()
    }

    private fun updateNotification(
        stopName: String?,
        predictions: List<ArrivalPrediction>,
        lastUpdateEpoch: Long?
    ) {
        val notification = buildNotification(stopName, predictions, lastUpdateEpoch)
        val nm = NotificationManagerCompat.from(this)
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission denied: ${e.message}")
        }
    }
}