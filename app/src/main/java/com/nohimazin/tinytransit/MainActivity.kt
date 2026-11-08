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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import com.nohimazin.tinytransit.data.db.entity.StopEntity
import com.nohimazin.tinytransit.data.realtime.GtfsRealtimeRepository
import com.nohimazin.tinytransit.data.realtime.StopArrival
import com.nohimazin.tinytransit.data.repository.GtfsRepository
import com.nohimazin.tinytransit.service.RealtimeMonitorService
import com.nohimazin.tinytransit.ui.theme.TinyTransitTheme

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

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<StopEntity>>(emptyList()) }
    var selectedStop by remember { mutableStateOf<StopEntity?>(null) }

    var routesForStop by remember { mutableStateOf<List<com.nohimazin.tinytransit.data.db.entity.RouteEntity>>(emptyList()) }
    var selectedRouteId by remember { mutableStateOf<String?>(null) }
    var routeSearchQuery by remember { mutableStateOf("") }
    var routeFilterExpanded by remember { mutableStateOf(false) }

    var allArrivals by remember { mutableStateOf<List<StopArrival>>(emptyList()) }
    var arrivals by remember { mutableStateOf<List<StopArrival>>(emptyList()) }
    var arrivalStatus by remember { mutableStateOf("") }
    var arrivalRunning by remember { mutableStateOf(false) }
    var fetchTrigger by remember { mutableStateOf(0) }
    var selectedTripId by remember { mutableStateOf<String?>(null) }

    var monitoring by remember { mutableStateOf(RealtimeMonitorService.isRunning) }
    var networkStateText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
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
            importStatus = "インポート完了"
        } catch (e: Exception) {
            importStatus = "エラー: ${e.message}"
        } finally {
            importRunning = false
        }
    }

    LaunchedEffect(searchQuery) {
        // 選択された停留所の名前と一致する場合は検索しない（二重検索防止）
        if (selectedStop != null && searchQuery == selectedStop!!.stopName) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        if (searchQuery.length >= 2) {
            searchResults = gtfsRepository.searchStops(GtfsRepository.DEFAULT_FEED_ID, searchQuery)
        } else {
            searchResults = emptyList()
        }
    }

    LaunchedEffect(selectedStop) {
        val stop = selectedStop ?: return@LaunchedEffect
        routesForStop = gtfsRepository.getRoutesForStop(GtfsRepository.DEFAULT_FEED_ID, stop.stopId)
        selectedRouteId = null
    }

    LaunchedEffect(fetchTrigger) {
        if (fetchTrigger == 0) return@LaunchedEffect
        val stop = selectedStop
        if (stop == null) {
            arrivalStatus = "停留所を選択してください"
            return@LaunchedEffect
        }
        arrivalRunning = true
        arrivalStatus = "取得中..."
        try {
            val result = realtimeRepository.getArrivalsForStop(
                feedId = GtfsRepository.DEFAULT_FEED_ID,
                stopId = stop.stopId,
                url = "https://km.bus-vision.jp/realtime/sankobus_trip_update.bin"
            )
            allArrivals = result
            arrivals = if (selectedRouteId != null) {
                result.filter { it.prediction.routeId == selectedRouteId }
            } else {
                result
            }
            arrivalStatus = "到着予測: ${arrivals.size} 件"
        } catch (e: Exception) {
            arrivalStatus = "エラー: ${e.message}"
        } finally {
            arrivalRunning = false
        }
    }

    // 選択された便が変更されたら、監視中に通知を更新
    LaunchedEffect(selectedTripId) {
        if (monitoring) {
            val intent = Intent(context, RealtimeMonitorService::class.java).apply {
                action = RealtimeMonitorService.ACTION_UPDATE_SELECTION
                if (selectedTripId != null) {
                    putExtra(RealtimeMonitorService.EXTRA_SELECTED_TRIP_ID, selectedTripId)
                }
            }
            context.startService(intent)
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
            }
        }

        // Stop search
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("停留所名で検索") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Search results
        if (searchResults.isNotEmpty()) {
            items(searchResults.take(10)) { stop ->
                Button(
                    onClick = {
                        selectedStop = stop
                        searchQuery = stop.stopName
                        searchResults = emptyList()
                        allArrivals = emptyList()
                        arrivals = emptyList()
                        arrivalStatus = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${stop.stopName} (${GtfsRepository.baseStopId(stop.stopId)})")
                }
            }
        }

        // Selected stop info
        selectedStop?.let { stop ->
            item {
                Text(text = "選択: ${stop.stopName}", modifier = Modifier.padding(top = 8.dp))
            }

            // Route filter (collapsible)
            if (routesForStop.size > 1) {
                item {
                    Button(
                        onClick = { routeFilterExpanded = !routeFilterExpanded },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Text(if (routeFilterExpanded) "路線フィルターを閉じる" else "路線フィルターを開く")
                    }
                }
                if (routeFilterExpanded) {
                    item {
                        OutlinedTextField(
                            value = routeSearchQuery,
                            onValueChange = { routeSearchQuery = it },
                            label = { Text("路線名で絞り込み") },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }
                    item {
                        val filteredRoutes = if (routeSearchQuery.isBlank()) {
                            routesForStop
                        } else {
                            routesForStop.filter { r ->
                                (r.routeLongName ?: "").contains(routeSearchQuery, ignoreCase = true) ||
                                    (r.routeShortName ?: "").contains(routeSearchQuery, ignoreCase = true)
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = selectedRouteId == null,
                                onClick = {
                                    selectedRouteId = null
                                    arrivals = allArrivals
                                    arrivalStatus = "到着予測: ${arrivals.size} 件"
                                },
                                label = { Text("全て") }
                            )
                            filteredRoutes.forEach { route ->
                                FilterChip(
                                    selected = selectedRouteId == route.routeId,
                                    onClick = {
                                        selectedRouteId = route.routeId
                                        arrivals = allArrivals.filter { it.prediction.routeId == route.routeId }
                                        arrivalStatus = "到着予測: ${arrivals.size} 件"
                                    },
                                    label = { Text(route.routeLongName ?: route.routeShortName ?: route.routeId) }
                                )
                            }
                        }
                    }
                }
            }

            // Action buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { fetchTrigger++ },
                        enabled = !arrivalRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取得")
                    }
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
                                val intent = Intent(context, RealtimeMonitorService::class.java).apply {
                                    putExtra(RealtimeMonitorService.EXTRA_FEED_ID, GtfsRepository.DEFAULT_FEED_ID)
                                    putExtra(RealtimeMonitorService.EXTRA_STOP_ID, stop.stopId)
                                    putExtra(RealtimeMonitorService.EXTRA_STOP_NAME, stop.stopName)
                                    if (selectedTripId != null) {
                                        putExtra(RealtimeMonitorService.EXTRA_SELECTED_TRIP_ID, selectedTripId)
                                    }
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

            // Network state
            item {
                Text(text = "ネットワーク: $networkStateText", modifier = Modifier.padding(top = 8.dp))
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