package com.nohimazin.tinytransit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.nohimazin.tinytransit.data.realtime.GtfsRealtimeRepository
import com.nohimazin.tinytransit.data.repository.GtfsRepository
import com.nohimazin.tinytransit.ui.theme.TinyTransitTheme
import kotlinx.coroutines.launch

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
    var realtimeStatus by remember { mutableStateOf("") }
    var verifyStatus by remember { mutableStateOf("") }
    var fetchTrigger by remember { mutableStateOf(0) }
    var verifyTrigger by remember { mutableStateOf(0) }

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
        try {
            val predictions = realtimeRepository.fetchAndDecodeTripUpdates(
                feedId = GtfsRepository.DEFAULT_FEED_ID,
                url = "https://km.bus-vision.jp/realtime/sankobus_trip_update.bin"
            )
            val sb = StringBuilder()
            sb.appendLine("TripUpdate 件数: ${predictions.size}")
            sb.appendLine("ユニーク便数: ${predictions.map { it.tripId }.distinct().size}")
            predictions.take(5).forEach { p ->
                sb.appendLine("${p.tripId} stop=${p.stopId} seq=${p.stopSequence} delay=${p.delaySeconds}s predicted=${p.predictedArrivalEpoch} skipped=${p.isSkipped} canceled=${p.isCanceled}")
            }
            realtimeStatus = sb.toString()
        } catch (e: Exception) {
            realtimeStatus = "エラー: ${e.message}"
        }
    }

    LaunchedEffect(verifyTrigger) {
        if (verifyTrigger == 0) return@LaunchedEffect
        try {
            val result = realtimeRepository.verifyRealtimeIntegrity(
                feedId = GtfsRepository.DEFAULT_FEED_ID,
                url = "https://km.bus-vision.jp/realtime/sankobus_trip_update.bin"
            )
            verifyStatus = result.toString()
        } catch (e: Exception) {
            verifyStatus = "エラー: ${e.message}"
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (importRunning) {
            CircularProgressIndicator(progress = { importProgress })
        }
        Text(text = importStatus)
        if (!importRunning) {
            Text(text = "進捗: ${(importProgress * 100).toInt()}%")
            Button(onClick = { fetchTrigger++ }) {
                Text("TripUpdate を取得")
            }
            Button(onClick = { verifyTrigger++ }) {
                Text("照合結果を検証")
            }
        }
        if (realtimeStatus.isNotEmpty()) {
            Text(text = realtimeStatus)
        }
        if (verifyStatus.isNotEmpty()) {
            Text(text = verifyStatus)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    TinyTransitTheme {
    }
}