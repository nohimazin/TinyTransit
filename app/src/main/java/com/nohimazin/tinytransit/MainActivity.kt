package com.nohimazin.tinytransit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.lifecycleScope
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
                    ImportScreen(
                        repository = app.gtfsRepository,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun ImportScreen(repository: GtfsRepository, modifier: Modifier = Modifier) {
    var status by remember { mutableStateOf("初期化中...") }
    var progress by remember { mutableStateOf(0f) }
    var running by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        running = true
        try {
            repository.importStaticGtfs(
                feedId = GtfsRepository.DEFAULT_FEED_ID,
                url = "https://km.bus-vision.jp/gtfs/sankobus/gtfsFeed",
                onProgress = { p, msg ->
                    progress = p
                    status = msg
                }
            )
            status = "インポート完了"
        } catch (e: Exception) {
            status = "エラー: ${e.message}"
        } finally {
            running = false
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (running) {
            CircularProgressIndicator(progress = { progress })
        }
        Text(text = status)
        Text(text = "進捗: ${(progress * 100).toInt()}%")
    }
}

@Preview(showBackground = true)
@Composable
fun ImportScreenPreview() {
    TinyTransitTheme {
        ImportScreen(
            repository = GtfsRepository(
                // Preview では実際のインポートは行わない
                context = androidx.compose.ui.platform.LocalContext.current,
                database = androidx.room.Room.inMemoryDatabaseBuilder(
                    androidx.compose.ui.platform.LocalContext.current,
                    com.nohimazin.tinytransit.data.db.TinyTransitDatabase::class.java
                ).build(),
                httpClient = okhttp3.OkHttpClient()
            )
        )
    }
}