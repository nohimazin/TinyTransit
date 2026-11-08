package com.nohimazin.tinytransit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nohimazin.tinytransit.data.db.entity.MonitoredRouteEntity
import com.nohimazin.tinytransit.data.db.entity.StopEntity
import com.nohimazin.tinytransit.data.realtime.GtfsRealtimeRepository
import com.nohimazin.tinytransit.data.realtime.StopArrival
import com.nohimazin.tinytransit.data.repository.GtfsRepository
import com.nohimazin.tinytransit.service.RealtimeMonitorService
import com.nohimazin.tinytransit.ui.theme.TinyTransitTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TinyTransitApp
        setContent {
            TinyTransitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        activity = this,
                        gtfsRepository = app.gtfsRepository,
                        realtimeRepository = GtfsRealtimeRepository(app, app.database, app.realtimeHttpClient),
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

@Composable
fun MainScreen(
    activity: MainActivity,
    gtfsRepository: GtfsRepository,
    realtimeRepository: GtfsRealtimeRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var importStatus by remember { mutableStateOf("初期化中...") }
    var importProgress by remember { mutableStateOf(0f) }
    var importRunning by remember { mutableStateOf(true) }

    // 画面切り替え: false = ホーム, true = 検索/設定
    var showSearch by remember { mutableStateOf(false) }

    // ホーム画面用
    var monitoredRoutes by remember { mutableStateOf<List<MonitoredRouteEntity>>(emptyList()) }
    var homeArrivals by remember { mutableStateOf<Map<String, List<StopArrival>>>(emptyMap()) }
    var homeStatus by remember { mutableStateOf("") }
    var homeRunning by remember { mutableStateOf(false) }
    var homeFetchTrigger by remember { mutableStateOf(0) }

    // 検索画面用
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<StopEntity>>(emptyList()) }
    var selectedOrigin by remember { mutableStateOf<StopEntity?>(null) }
    var destSearchQuery by remember { mutableStateOf("") }
    var destSearchResults by remember { mutableStateOf<List<StopEntity>>(emptyList()) }
    var selectedDest by remember { mutableStateOf<StopEntity?>(null) }
    var arrivals by remember { mutableStateOf<List<StopArrival>>(emptyList()) }
    var arrivalStatus by remember { mutableStateOf("") }
    var arrivalRunning by remember { mutableStateOf(false) }
    var fetchTrigger by remember { mutableStateOf(0) }
    var updateTrigger by remember { mutableStateOf(0) }
    var selectedTripId by remember { mutableStateOf<String?>(null) }

    // バックグラウンド監視
    var monitoring by remember { mutableStateOf(RealtimeMonitorService.isRunning) }
    var networkStateText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        importRunning = true
        try {
            val existingCount = gtfsRepository.getStopCount(GtfsRepository.DEFAULT_FEED_ID)
            if (existingCount > 0) {
                importStatus = "インポート済み (${existingCount} 停留所)"
                importProgress = 1f
            } else {
                gtfsRepository.importStaticGtfs(
                    feedId = GtfsRepository.DEFAULT_FEED_ID,
                    url = "https://km.bus-vision.jp/gtfs/sankobus/gtfsFeed",
                    onProgress = { p, msg ->
                        importProgress = p
                        importStatus = msg
                    }
                )
                importStatus = "インポート完了"
            }
            monitoredRoutes = gtfsRepository.getMonitoredRoutes(GtfsRepository.DEFAULT_FEED_ID)
        } catch (e: Exception) {
            importStatus = "エラー: ${e.message}"
        } finally {
            importRunning = false
        }
    }

    LaunchedEffect(updateTrigger) {
        if (updateTrigger == 0) return@LaunchedEffect
        importRunning = true
        try {
            gtfsRepository.importStaticGtfs(
                feedId = GtfsRepository.DEFAULT_FEED_ID,
                url = "https://km.bus-vision.jp/gtfs/sankobus/gtfsFeed",
                onProgress = { p, msg ->
                    importProgress = p
                    importStatus = msg
                }
            )
            importStatus = "更新完了"
            monitoredRoutes = gtfsRepository.getMonitoredRoutes(GtfsRepository.DEFAULT_FEED_ID)
        } catch (e: Exception) {
            importStatus = "エラー: ${e.message}"
        } finally {
            importRunning = false
        }
    }

    // ホーム画面: 監視対象経路の到着予測を取得
    LaunchedEffect(homeFetchTrigger) {
        if (homeFetchTrigger == 0 || monitoredRoutes.isEmpty()) return@LaunchedEffect
        homeRunning = true
        homeStatus = "取得中..."
        try {
            val allPredictions = mutableMapOf<String, List<StopArrival>>()
            for (route in monitoredRoutes) {
                val result = realtimeRepository.getArrivalsForStop(
                    feedId = GtfsRepository.DEFAULT_FEED_ID,
                    stopId = route.originStopId,
                    url = "https://km.bus-vision.jp/realtime/sankobus_trip_update.bin"
                )
                // 降車停留所を経由する便のみ
                val commonTrips = gtfsRepository.findTripsBetweenStops(
                    GtfsRepository.DEFAULT_FEED_ID,
                    route.originStopId,
                    route.destStopId
                ).toSet()
                val filtered = if (commonTrips.isNotEmpty()) {
                    result.filter { it.prediction.tripId in commonTrips }
                } else {
                    result
                }
                allPredictions["${route.originStopId}->${route.destStopId}"] = filtered
            }
            homeArrivals = allPredictions
            homeStatus = "到着予測: ${homeArrivals.values.sumOf { it.size }} 件"
        } catch (e: Exception) {
            homeStatus = "エラー: ${e.message}"
        } finally {
            homeRunning = false
        }
    }

    // 検索画面: 乗車停留所検索
    LaunchedEffect(searchQuery) {
        if (selectedOrigin != null && searchQuery == selectedOrigin!!.stopName) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        if (searchQuery.length >= 2) {
            searchResults = gtfsRepository.searchStops(GtfsRepository.DEFAULT_FEED_ID, searchQuery)
        } else {
            searchResults = emptyList()
        }
    }

    // 検索画面: 降車停留所検索
    LaunchedEffect(destSearchQuery) {
        if (selectedDest != null && destSearchQuery == selectedDest!!.stopName) {
            destSearchResults = emptyList()
            return@LaunchedEffect
        }
        if (destSearchQuery.length >= 2) {
            destSearchResults = gtfsRepository.searchStops(GtfsRepository.DEFAULT_FEED_ID, destSearchQuery)
        } else {
            destSearchResults = emptyList()
        }
    }

    // 検索画面: 到着予測取得
    LaunchedEffect(fetchTrigger) {
        if (fetchTrigger == 0) return@LaunchedEffect
        val origin = selectedOrigin
        if (origin == null) {
            arrivalStatus = "乗車停留所を選択してください"
            return@LaunchedEffect
        }
        arrivalRunning = true
        arrivalStatus = "取得中..."
        try {
            val result = realtimeRepository.getArrivalsForStop(
                feedId = GtfsRepository.DEFAULT_FEED_ID,
                stopId = origin.stopId,
                url = "https://km.bus-vision.jp/realtime/sankobus_trip_update.bin"
            )
            val filtered = if (selectedDest != null) {
                val commonTrips = gtfsRepository.findTripsBetweenStops(
                    GtfsRepository.DEFAULT_FEED_ID,
                    origin.stopId,
                    selectedDest!!.stopId
                ).toSet()
                result.filter { it.prediction.tripId in commonTrips }
            } else {
                result
            }
            arrivals = filtered
            arrivalStatus = "到着予測: ${arrivals.size} 件" + if (selectedDest != null) " (${selectedDest!!.stopName} 行き)" else ""
        } catch (e: Exception) {
            arrivalStatus = "エラー: ${e.message}"
        } finally {
            arrivalRunning = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            monitoring = RealtimeMonitorService.isRunning
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            networkStateText = when {
                caps == null -> "オフライン"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "モバイル"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "その他"
            }
            kotlinx.coroutines.delay(2000)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Import status
        item {
            if (importRunning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(progress = { importProgress }, modifier = Modifier.padding(end = 8.dp))
                    Text(text = importStatus)
                }
            } else {
                Text(text = importStatus)
                Button(
                    onClick = { updateTrigger++ },
                    enabled = !importRunning,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("GTFS を更新")
                }
            }
        }

        if (showSearch) {
            // === 検索/設定画面 ===
            item {
                Button(onClick = { showSearch = false }) {
                    Text("← ホームに戻る")
                }
            }

            // 乗車停留所検索
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("乗車停留所を検索") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (searchResults.isNotEmpty()) {
                items(searchResults.take(10)) { stop ->
                    Button(
                        onClick = {
                            selectedOrigin = stop
                            searchQuery = stop.stopName
                            searchResults = emptyList()
                            arrivals = emptyList()
                            arrivalStatus = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${stop.stopName} (${GtfsRepository.baseStopId(stop.stopId)})")
                    }
                }
            }

            selectedOrigin?.let { origin ->
                item {
                    Text(text = "乗車: ${origin.stopName}", modifier = Modifier.padding(top = 8.dp))
                }
            }

            // 降車停留所検索
            item {
                OutlinedTextField(
                    value = destSearchQuery,
                    onValueChange = { destSearchQuery = it },
                    label = { Text("降車停留所を検索 (任意)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }

            if (destSearchResults.isNotEmpty()) {
                items(destSearchResults.take(10)) { stop ->
                    Button(
                        onClick = {
                            selectedDest = stop
                            destSearchQuery = stop.stopName
                            destSearchResults = emptyList()
                            arrivals = emptyList()
                            arrivalStatus = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${stop.stopName} (${GtfsRepository.baseStopId(stop.stopId)})")
                    }
                }
            }

            selectedDest?.let { dest ->
                item {
                    Text(text = "降車: ${dest.stopName}", modifier = Modifier.padding(top = 4.dp))
                }
            }

            // 監視対象に追加 (乗車+降車のセット)
            if (selectedOrigin != null) {
                item {
                    Button(
                        onClick = {
                            val origin = selectedOrigin!!
                            val dest = selectedDest
                            val app = context.applicationContext as TinyTransitApp
                            kotlinx.coroutines.GlobalScope.launch {
                                app.database.monitoredRouteDao().upsert(
                                    MonitoredRouteEntity(
                                        feedId = GtfsRepository.DEFAULT_FEED_ID,
                                        originStopId = GtfsRepository.baseStopId(origin.stopId),
                                        originStopName = origin.stopName,
                                        destStopId = if (dest != null) GtfsRepository.baseStopId(dest.stopId) else GtfsRepository.baseStopId(origin.stopId),
                                        destStopName = dest?.stopName ?: origin.stopName
                                    )
                                )
                                monitoredRoutes = app.database.monitoredRouteDao().getAll(GtfsRepository.DEFAULT_FEED_ID)
                                showSearch = false
                            }
                        }
                    ) {
                        Text(if (selectedDest != null) "監視対象に追加 (乗車→降車)" else "監視対象に追加")
                    }
                }
            }

            // 取得ボタン
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { fetchTrigger++ },
                        enabled = !arrivalRunning && selectedOrigin != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取得")
                    }
                }
            }

            // Status
            if (arrivalStatus.isNotEmpty()) {
                item {
                    Text(text = arrivalStatus, modifier = Modifier.padding(top = 4.dp))
                }
            }

            if (arrivalRunning) {
                item {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
            }

            // Arrivals
            items(arrivals) { a ->
                val isSelected = selectedTripId == a.prediction.tripId
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "${a.routeShortName ?: ""} ${a.routeLongName ?: ""} → ${a.tripHeadsign ?: ""}",
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                selectedTripId = if (isSelected) null else a.prediction.tripId
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(if (isSelected) "選択中" else "選択")
                        }
                    }
                    Text(text = "停留所: ${a.stopName ?: GtfsRepository.baseStopId(a.prediction.stopId)}")
                    Text(text = "予定: ${formatSeconds(a.prediction.scheduledArrivalSeconds)}  予測: ${a.prediction.predictedTimeText()}  ${a.prediction.delayText()}")
                    val mins = a.prediction.minutesUntil(System.currentTimeMillis() / 1000)
                    if (mins != null) {
                        Text(text = "あと ${mins} 分")
                    }
                }
            }
        } else {
            // === ホーム画面 ===
            item {
                Text(text = "監視対象", modifier = Modifier.padding(top = 8.dp))
            }

            if (monitoredRoutes.isEmpty()) {
                item {
                    Text(text = "監視対象がありません。検索/設定から追加してください。", modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                items(monitoredRoutes) { route ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(text = "${route.originStopName} → ${route.destStopName}")
                        Button(
                            onClick = {
                                val app = context.applicationContext as TinyTransitApp
                                kotlinx.coroutines.GlobalScope.launch {
                                    app.database.monitoredRouteDao().deleteByRoute(
                                        GtfsRepository.DEFAULT_FEED_ID,
                                        route.originStopId,
                                        route.destStopId
                                    )
                                    monitoredRoutes = app.database.monitoredRouteDao().getAll(GtfsRepository.DEFAULT_FEED_ID)
                                }
                            }
                        ) {
                            Text("削除")
                        }
                    }
                }
            }

            // ホーム画面の到着予測
            monitoredRoutes.forEach { route ->
                val routeKey = "${route.originStopId}->${route.destStopId}"
                val routeArrivals = homeArrivals[routeKey] ?: emptyList()
                if (routeArrivals.isNotEmpty()) {
                    item {
                        Text(text = "${route.originStopName} → ${route.destStopName}", modifier = Modifier.padding(top = 8.dp))
                    }
                    items(routeArrivals) { a ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(text = "${a.routeShortName ?: ""} ${a.routeLongName ?: ""} → ${a.tripHeadsign ?: ""}")
                            Text(text = "予定: ${formatSeconds(a.prediction.scheduledArrivalSeconds)}  予測: ${a.prediction.predictedTimeText()}  ${a.prediction.delayText()}")
                            val mins = a.prediction.minutesUntil(System.currentTimeMillis() / 1000)
                            if (mins != null) {
                                Text(text = "あと ${mins} 分")
                            }
                        }
                    }
                }
            }

            if (homeStatus.isNotEmpty()) {
                item {
                    Text(text = homeStatus, modifier = Modifier.padding(top = 4.dp))
                }
            }

            // アクションボタン
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { homeFetchTrigger++ },
                        enabled = !homeRunning && monitoredRoutes.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("更新")
                    }
                    Button(
                        onClick = { showSearch = true },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text("検索/設定")
                    }
                }
            }

            // バックグラウンド監視
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "監視: ${if (monitoring) "ON" else "OFF"}")
                    Button(
                        onClick = {
                            if (monitoring) {
                                val intent = Intent(context, RealtimeMonitorService::class.java).apply {
                                    action = RealtimeMonitorService.ACTION_STOP
                                }
                                context.startService(intent)
                                monitoring = false
                            } else {
                                if (!activity.hasNotificationPermission()) {
                                    activity.requestNotificationPermission()
                                    return@Button
                                }
                                val ms = monitoredRoutes.firstOrNull() ?: return@Button
                                val intent = Intent(context, RealtimeMonitorService::class.java).apply {
                                    putExtra(RealtimeMonitorService.EXTRA_FEED_ID, GtfsRepository.DEFAULT_FEED_ID)
                                    putExtra(RealtimeMonitorService.EXTRA_STOP_ID, ms.originStopId)
                                    putExtra(RealtimeMonitorService.EXTRA_STOP_NAME, ms.originStopName)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                                monitoring = true
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(if (monitoring) "監視停止" else "監視開始")
                    }
                }
            }

            // Network state
            item {
                Text(text = "ネットワーク: $networkStateText", modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

fun formatSeconds(seconds: Long?): String {
    if (seconds == null) return "--:--"
    val h = (seconds / 3600) % 24
    val m = (seconds % 3600) / 60
    return String.format("%02d:%02d", h, m)
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    TinyTransitTheme {
    }
}