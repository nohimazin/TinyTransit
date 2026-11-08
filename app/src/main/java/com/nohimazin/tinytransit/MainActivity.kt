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
    var updateTrigger by remember { mutableStateOf(0) }

    // 乗車停留所
    var originSearchQuery by remember { mutableStateOf("") }
    var originSearchResults by remember { mutableStateOf<List<StopEntity>>(emptyList()) }
    var originStop by remember { mutableStateOf<StopEntity?>(null) }

    // 降車停留所
    var destSearchQuery by remember { mutableStateOf("") }
    var destSearchResults by remember { mutableStateOf<List<StopEntity>>(emptyList()) }
    var destStop by remember { mutableStateOf<StopEntity?>(null) }

    // 到着予測
    var arrivals by remember { mutableStateOf<List<StopArrival>>(emptyList()) }
    var arrivalStatus by remember { mutableStateOf("") }
    var arrivalRunning by remember { mutableStateOf(false) }
    var fetchTrigger by remember { mutableStateOf(0) }
    var selectedTripId by remember { mutableStateOf<String?>(null) }

    // バックグラウンド監視
    var monitoring by remember { mutableStateOf(RealtimeMonitorService.isRunning) }
    var networkStateText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        importRunning = true
        try {
            // 既にデータが存在する場合はインポートをスキップ
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
        } catch (e: Exception) {
            importStatus = "エラー: ${e.message}"
        } finally {
            importRunning = false
        }
    }

    LaunchedEffect(originSearchQuery) {
        if (originStop != null && originSearchQuery == originStop!!.stopName) {
            originSearchResults = emptyList()
            return@LaunchedEffect
        }
        if (originSearchQuery.length >= 2) {
            originSearchResults = gtfsRepository.searchStops(GtfsRepository.DEFAULT_FEED_ID, originSearchQuery)
        } else {
            originSearchResults = emptyList()
        }
    }

    LaunchedEffect(destSearchQuery) {
        if (destStop != null && destSearchQuery == destStop!!.stopName) {
            destSearchResults = emptyList()
            return@LaunchedEffect
        }
        if (destSearchQuery.length >= 2) {
            destSearchResults = gtfsRepository.searchStops(GtfsRepository.DEFAULT_FEED_ID, destSearchQuery)
        } else {
            destSearchResults = emptyList()
        }
    }

    LaunchedEffect(fetchTrigger) {
        if (fetchTrigger == 0) return@LaunchedEffect
        val origin = originStop
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
            val filtered = if (destStop != null) {
                val commonTrips = gtfsRepository.findTripsBetweenStops(
                    GtfsRepository.DEFAULT_FEED_ID,
                    origin.stopId,
                    destStop!!.stopId
                ).toSet()
                result.filter { it.prediction.tripId in commonTrips }
            } else {
                result
            }
            arrivals = filtered
            arrivalStatus = "到着予測: ${arrivals.size} 件" + if (destStop != null) " (${destStop!!.stopName} 行き)" else ""
        } catch (e: Exception) {
            arrivalStatus = "エラー: ${e.message}"
        } finally {
            arrivalRunning = false
        }
    }

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
                Button(
                    onClick = { updateTrigger++ },
                    enabled = !importRunning,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("GTFS を更新")
                }
            }
        }

        // 乗車停留所検索
        item {
            OutlinedTextField(
                value = originSearchQuery,
                onValueChange = { originSearchQuery = it },
                label = { Text("乗車停留所を検索") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (originSearchResults.isNotEmpty()) {
            items(originSearchResults.take(10)) { stop ->
                Button(
                    onClick = {
                        originStop = stop
                        originSearchQuery = stop.stopName
                        originSearchResults = emptyList()
                        arrivals = emptyList()
                        arrivalStatus = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${stop.stopName} (${GtfsRepository.baseStopId(stop.stopId)})")
                }
            }
        }

        originStop?.let { origin ->
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
                        destStop = stop
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

        destStop?.let { dest ->
            item {
                Text(text = "降車: ${dest.stopName}", modifier = Modifier.padding(top = 4.dp))
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
                    enabled = !arrivalRunning && originStop != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取得")
                }
                Button(
                    onClick = {
                        val origin = originStop ?: return@Button
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
                                putExtra(RealtimeMonitorService.EXTRA_STOP_ID, origin.stopId)
                                putExtra(RealtimeMonitorService.EXTRA_STOP_NAME, origin.stopName)
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