package com.nohimazin.tinytransit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nohimazin.tinytransit.data.realtime.GtfsRealtimeRepository
import com.nohimazin.tinytransit.data.realtime.StopArrival
import com.nohimazin.tinytransit.data.repository.GtfsRepository
import com.nohimazin.tinytransit.ui.theme.TinyTransitTheme
import java.time.Instant
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TinyTransitApp
        setContent {
            TinyTransitTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        gtfsRepository = app.gtfsRepository,
                        realtimeRepository = GtfsRealtimeRepository(app, app.database, app.okHttpClient),
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    gtfsRepository: GtfsRepository,
    realtimeRepository: GtfsRealtimeRepository,
    modifier: Modifier = Modifier
) {
    var importStatus by remember { mutableStateOf("初期化中...") }
    var importProgress by remember { mutableStateOf(0f) }
    var importRunning by remember { mutableStateOf(true) }
    var stopId by remember { mutableStateOf("") }
    var arrivals by remember { mutableStateOf<List<StopArrival>>(emptyList()) }
    var arrivalStatus by remember { mutableStateOf("") }
    var arrivalRunning by remember { mutableStateOf(false) }
    var fetchTrigger by remember { mutableStateOf(0) }

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

    LaunchedEffect(fetchTrigger) {
        if (fetchTrigger == 0) return@LaunchedEffect
        if (stopId.isBlank()) {
            arrivalStatus = "停留所IDを入力してください"
            return@LaunchedEffect
        }
        arrivalRunning = true
        arrivalStatus = "取得中..."
        try {
            val result = realtimeRepository.getArrivalsForStop(
                feedId = GtfsRepository.DEFAULT_FEED_ID,
                stopId = stopId.trim(),
                url = "https://km.bus-vision.jp/realtime/sankobus_trip_update.bin"
            )
            arrivals = result
            arrivalStatus = "到着予測: ${result.size} 件"
        } catch (e: Exception) {
            arrivalStatus = "エラー: ${e.message}"
        } finally {
            arrivalRunning = false
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (importRunning) {
            CircularProgressIndicator(progress = { importProgress })
            Text(text = importStatus)
        } else {
            Text(text = importStatus)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = stopId,
                    onValueChange = { stopId = it },
                    label = { Text("停留所ID") },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { fetchTrigger++ },
                    enabled = !arrivalRunning,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("取得")
                }
            }
            if (arrivalRunning) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }
            if (arrivalStatus.isNotEmpty()) {
                Text(text = arrivalStatus, modifier = Modifier.padding(top = 8.dp))
            }
            arrivals.forEach { a ->
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(text = "${a.routeShortName ?: ""} ${a.routeLongName ?: ""} → ${a.tripHeadsign ?: ""}")
                    Text(text = "停留所: ${a.stopName ?: a.prediction.stopId}")
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

private fun formatSeconds(seconds: Long?): String {
    if (seconds == null) return "--:--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return String.format("%02d:%02d", h, m)
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    TinyTransitTheme {
    }
}