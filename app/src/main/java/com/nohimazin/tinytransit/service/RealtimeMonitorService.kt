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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nohimazin.tinytransit.MainActivity
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
        const val NOTIFICATION_ID_BASE = 1001
        const val CHANNEL_ID = "realtime_monitor"
        const val ACTION_STOP = "com.nohimazin.tinytransit.ACTION_STOP_MONITOR"
        const val ACTION_ADD_ROUTE = "com.nohimazin.tinytransit.ACTION_ADD_ROUTE"
        const val ACTION_REMOVE_ROUTE = "com.nohimazin.tinytransit.ACTION_REMOVE_ROUTE"
        const val EXTRA_ORIGIN_STOP_ID = "origin_stop_id"
        const val EXTRA_ORIGIN_STOP_NAME = "origin_stop_name"
        const val EXTRA_DEST_STOP_ID = "dest_stop_id"
        const val EXTRA_DEST_STOP_NAME = "dest_stop_name"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var controller: RealtimeUpdateController? = null

    // 監視中の経路セット
    private val monitoredRoutes = mutableSetOf<String>() // "originId->destId"
    private val monitoredRouteNames = mutableMapOf<String, String>() // key -> "originName → destName"
    private val monitoredOriginStopIds = mutableSetOf<String>() // 監視中の乗車停留所 ID
    private val routeNotificationIds = mutableMapOf<String, Int>() // routeKey -> notificationId

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 全停止
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        // 経路追加
        if (intent?.action == ACTION_ADD_ROUTE) {
            val originStopId = intent.getStringExtra(EXTRA_ORIGIN_STOP_ID) ?: return START_STICKY
            val originStopName = intent.getStringExtra(EXTRA_ORIGIN_STOP_NAME) ?: originStopId
            val destStopId = intent.getStringExtra(EXTRA_DEST_STOP_ID) ?: originStopId
            val destStopName = intent.getStringExtra(EXTRA_DEST_STOP_NAME) ?: originStopName
            val routeKey = "$originStopId->$destStopId"

            if (routeKey !in monitoredRoutes) {
                monitoredRoutes.add(routeKey)
                monitoredRouteNames[routeKey] = "$originStopName → $destStopName"
                monitoredOriginStopIds.add(originStopId)
            }

            if (!isRunning) {
                val notification = buildForegroundNotification()
                startForeground(NOTIFICATION_ID_BASE, notification)
                startMonitoring()
                isRunning = true
            } else {
                controller?.let { ctrl ->
                    serviceScope.launch { refreshNotification(ctrl) }
                }
            }
            return START_STICKY
        }

        // 経路削除
        if (intent?.action == ACTION_REMOVE_ROUTE) {
            val originStopId = intent.getStringExtra(EXTRA_ORIGIN_STOP_ID) ?: return START_STICKY
            val destStopId = intent.getStringExtra(EXTRA_DEST_STOP_ID) ?: originStopId
            val routeKey = "$originStopId->$destStopId"
            monitoredRoutes.remove(routeKey)
            monitoredRouteNames.remove(routeKey)

            // 通知をキャンセル
            routeNotificationIds.remove(routeKey)?.let { notifId ->
                NotificationManagerCompat.from(this).cancel(notifId)
            }

            if (monitoredRoutes.isEmpty()) {
                stopMonitoring()
            } else {
                // originStopId が他の経路でも使われているか確認
                val stillUsed = monitoredRoutes.any { it.startsWith("$originStopId->") }
                if (!stillUsed) monitoredOriginStopIds.remove(originStopId)
                controller?.let { ctrl ->
                    serviceScope.launch { refreshNotification(ctrl) }
                }
            }
            return START_STICKY
        }

        // 従来の単一停留所指定 (後方互換)
        val stopId = intent?.getStringExtra("stop_id")
        val stopName = intent?.getStringExtra("stop_name")
        if (stopId != null) {
            val routeKey = "$stopId->$stopId"
            if (routeKey !in monitoredRoutes) {
                monitoredRoutes.add(routeKey)
                monitoredRouteNames[routeKey] = stopName ?: stopId
                monitoredOriginStopIds.add(stopId)
            }
        }

        if (!isRunning) {
            val notification = buildForegroundNotification()
            startForeground(NOTIFICATION_ID_BASE, notification)
            startMonitoring()
            isRunning = true
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        val app = application as TinyTransitApp
        controller = RealtimeUpdateController(
            context = this,
            database = app.database,
            httpClient = app.realtimeHttpClient,
            scope = serviceScope
        ).apply {
            configure(
                feedId = GtfsRepository.DEFAULT_FEED_ID,
                stopId = monitoredOriginStopIds.firstOrNull(),
                tripUpdateUrl = "https://km.bus-vision.jp/realtime/sankobus_trip_update.bin"
            )
            start()
        }

        serviceScope.launch {
            controller?.latestPredictions?.collectLatest { predictions ->
                refreshNotification(controller!!)
            }
        }
    }

    private suspend fun refreshNotification(controller: RealtimeUpdateController) {
        val predictions = controller.latestPredictions.value
        val nowEpoch = System.currentTimeMillis() / 1000
        // GTFS-RT のフィード/便タイムスタンプを使用 (アプリの取得時刻ではない)
        val feedTimestamp = controller.latestPredictions.value.firstOrNull()?.tripUpdateTimestamp
            ?: controller.latestPredictions.value.firstOrNull()?.feedTimestamp

        // 各監視経路ごとに個別の通知を更新
        for (routeKey in monitoredRoutes) {
            val parts = routeKey.split("->")
            val originStopId = parts.getOrElse(0) { "" }
            val destStopId = parts.getOrElse(1) { originStopId }
            val routeName = monitoredRouteNames[routeKey] ?: routeKey

            val baseId = GtfsRepository.baseStopId(originStopId)
            var filtered = predictions.filter { GtfsRepository.baseStopId(it.stopId) == baseId }
                .filter { p ->
                    val pred = p.predictedArrivalEpoch ?: return@filter true
                    pred >= nowEpoch - 60
                }

            // 降車停留所を経由する便のみ (origin != dest の場合)
            if (destStopId != originStopId) {
                val destBaseId = GtfsRepository.baseStopId(destStopId)
                val allStops = try {
                    (application as TinyTransitApp).database.stopDao().getAll(GtfsRepository.DEFAULT_FEED_ID)
                } catch (e: Exception) {
                    emptyList()
                }
                val destVariantIds = allStops.filter { GtfsRepository.baseStopId(it.stopId) == destBaseId }
                    .map { it.stopId }.toSet()
                val effectiveDestIds = if (destVariantIds.isEmpty()) setOf(destStopId) else destVariantIds

                val commonTrips = try {
                    val destStopTimes = effectiveDestIds.flatMap { v ->
                        (application as TinyTransitApp).database.stopTimeDao()
                            .getByStop(GtfsRepository.DEFAULT_FEED_ID, v)
                    }
                    // 基底 ID の全方向の停留所から origin の stop_times を取得
                    val allStops = (application as TinyTransitApp).database.stopDao()
                        .getAll(GtfsRepository.DEFAULT_FEED_ID)
                    val originVariantIds = allStops.filter { GtfsRepository.baseStopId(it.stopId) == baseId }
                        .map { it.stopId }.toSet()
                    val effectiveOriginIds = if (originVariantIds.isEmpty()) setOf(originStopId) else originVariantIds
                    val originStopTimes = effectiveOriginIds.flatMap { v ->
                        (application as TinyTransitApp).database.stopTimeDao()
                            .getByStop(GtfsRepository.DEFAULT_FEED_ID, v)
                    }
                    // 同一 trip が同じ停留所を複数回経由する場合、最小の stopSequence を使用
                    val originSeqMap = originStopTimes.groupBy { it.tripId }
                        .mapValues { (_, sts) -> sts.minOf { it.stopSequence } }
                    destStopTimes.filter { dst ->
                        val originSeq = originSeqMap[dst.tripId]
                        originSeq != null && originSeq < dst.stopSequence
                    }.map { it.tripId }.toSet()
                } catch (e: Exception) {
                    emptySet()
                }
                // commonTrips が空でない場合のみフィルタ (空の場合はフィルタしない)
                if (commonTrips.isNotEmpty()) {
                    filtered = filtered.filter { it.tripId in commonTrips }
                }

                // GTFS-RT に該当便がない場合 → 静的時刻表にフォールバック
                if (filtered.isEmpty()) {
                    val staticArrivals = try {
                        controller?.getStaticArrivals(GtfsRepository.DEFAULT_FEED_ID, originStopId, destStopId)
                            ?.map { it.prediction } ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (staticArrivals.isNotEmpty()) {
                        filtered = staticArrivals
                    }
                }
            }

            // 選択された便がある場合はそれのみ
            // 選択フィルタリングは行わない (全便を表示)

            val notificationId = routeNotificationIds.getOrPut(routeKey) {
                NOTIFICATION_ID_BASE + routeKey.hashCode().and(0x7FFFFFFF) % 1000
            }
            val feedTimestampMs = feedTimestamp?.let { it * 1000 }
            updateRouteNotification(notificationId, routeName, filtered, feedTimestampMs)
        }
    }

    private fun updateRouteNotification(
        notificationId: Int,
        routeName: String,
        predictions: List<ArrivalPrediction>,
        lastUpdateEpoch: Long?
    ) {
        val notification = buildRouteNotification(routeName, predictions, lastUpdateEpoch)
        val nm = NotificationManagerCompat.from(this)
        try {
            nm.notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission denied: ${e.message}")
        }
    }

    private fun buildRouteNotification(
        routeName: String,
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

        val title = buildNotificationTitle(predictions, lastUpdateEpoch)
        val content = buildRouteNotificationContent(routeName, predictions, lastUpdateEpoch)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (lastUpdateEpoch != null && lastUpdateEpoch > 0) {
            builder.setWhen(lastUpdateEpoch)
            builder.setUsesChronometer(true)
        }

        return builder.build()
    }

    private fun buildRouteNotificationContent(
        routeName: String,
        predictions: List<ArrivalPrediction>,
        lastUpdateEpoch: Long?
    ): String {
        val sb = StringBuilder()
        sb.append("経路: $routeName\n")
        if (predictions.isEmpty()) {
            sb.append("到着予測なし")
        } else {
            val nearest = predictions.minByOrNull { it.predictedArrivalEpoch ?: Long.MAX_VALUE }
            if (nearest != null) {
                val app = application as TinyTransitApp
                val route = nearest.routeId?.let { rid ->
                    kotlinx.coroutines.runBlocking {
                        app.database.routeDao().get(nearest.feedId, rid)
                    }
                }
                val routeNameDisplay = route?.routeLongName ?: route?.routeShortName ?: nearest.routeId ?: ""
                val time = nearest.predictedTimeText()
                val delay = nearest.delayText()
                val mins = nearest.minutesUntil(System.currentTimeMillis() / 1000)
                val minsText = if (mins != null) "あと${mins}分" else ""
                sb.append("$routeNameDisplay - $time")
                sb.append("\n遅延: $delay")
                if (minsText.isNotEmpty()) {
                    sb.append("  $minsText")
                }
                if (!nearest.isRealtime) {
                    sb.append(" (時刻表)")
                }
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

    private fun stopMonitoring() {
        controller?.stop()
        controller = null
        isRunning = false
        // 全通知をキャンセル
        val nm = NotificationManagerCompat.from(this)
        for (notifId in routeNotificationIds.values) {
            nm.cancel(notifId)
        }
        routeNotificationIds.clear()
        monitoredRoutes.clear()
        monitoredRouteNames.clear()
        monitoredOriginStopIds.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
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

        val title = buildNotificationTitle(predictions, lastUpdateEpoch)
        val content = buildNotificationContent(predictions, lastUpdateEpoch)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (lastUpdateEpoch != null && lastUpdateEpoch > 0) {
            builder.setWhen(lastUpdateEpoch)
            builder.setUsesChronometer(true)
        }

        return builder.build()
    }

    private fun buildForegroundNotification(): Notification {
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

        val routeCount = monitoredRoutes.size
        val title = "TinyTransit - 監視中 ($routeCount 経路)"
        val content = monitoredRouteNames.values.joinToString("\n").ifEmpty { "監視中..." }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildNotificationTitle(
        predictions: List<ArrivalPrediction>,
        lastUpdateEpoch: Long?
    ): String {
        if (predictions.isEmpty()) return "到着予測なし"
        // 監視中の全経路の最も近い便を表示
        val nearest = predictions.minByOrNull { it.predictedArrivalEpoch ?: Long.MAX_VALUE } ?: return "到着予測なし"
        val mins = nearest.minutesUntil(System.currentTimeMillis() / 1000)
        val minsText = if (mins != null) "あと${mins}分" else "到着済み"
        val delay = nearest.delayText()
        val tripTimestamp = nearest.tripUpdateTimestamp ?: (lastUpdateEpoch?.let { it / 1000 })
        val timeText = if (tripTimestamp != null && tripTimestamp > 0) {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Tokyo")
            sdf.format(java.util.Date(tripTimestamp * 1000))
        } else ""
        val isStatic = nearest.isRealtime == false
        val staticSuffix = if (isStatic) " (時刻表)" else ""
        return if (timeText.isNotEmpty()) {
            "$minsText・$delay・${timeText}時点$staticSuffix"
        } else {
            "$minsText・$delay$staticSuffix"
        }
    }

    private fun buildNotificationContent(
        predictions: List<ArrivalPrediction>,
        lastUpdateEpoch: Long?
    ): String {
        val sb = StringBuilder()
        // 監視中の経路名を表示
        if (monitoredRouteNames.isNotEmpty()) {
            sb.append(monitoredRouteNames.values.joinToString("\n"))
            sb.append("\n")
        }
        if (predictions.isEmpty()) {
            sb.append("到着予測なし")
        } else {
            // 経路ごとにグループ化
            val byRoute = predictions.groupBy { it.tripId }
            for ((_, preds) in byRoute) {
                val nearest = preds.minByOrNull { it.predictedArrivalEpoch ?: Long.MAX_VALUE } ?: continue
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
                sb.append("$routeName - $time ($delay) $minsText\n")
                sb.append("遅延: $delay")
                if (minsText.isNotEmpty()) {
                    sb.append("  $minsText")
                }
                sb.append("\n")
            }
        }
        if (lastUpdateEpoch != null && lastUpdateEpoch > 0) {
            val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(
                java.util.Date(lastUpdateEpoch)
            )
            sb.append("更新: $timeStr")
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
            nm.notify(NOTIFICATION_ID_BASE, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission denied: ${e.message}")
        }
    }
}