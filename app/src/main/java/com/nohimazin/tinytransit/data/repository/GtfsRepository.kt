package com.nohimazin.tinytransit.data.repository

import android.content.Context
import com.nohimazin.tinytransit.data.db.TinyTransitDatabase
import com.nohimazin.tinytransit.data.db.entity.FeedEntity
import com.nohimazin.tinytransit.data.gtfs.GtfsCsvReader
import com.nohimazin.tinytransit.data.gtfs.GtfsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 静的 GTFS の取得・展開・DB 保存を担うリポジトリ。
 * メモリ使用量を抑えるため、CSV はストリームで読み込み、
 * stop_times など大量データはバッチで挿入します。
 */
class GtfsRepository(
    private val context: Context,
    private val database: TinyTransitDatabase,
    private val httpClient: OkHttpClient
) {
    companion object {
        const val DEFAULT_FEED_ID = "sankobus"
    }

    /**
     * GTFS フィードをダウンロードし、DB に取り込みます。
     * @param feedId 事業者識別子
     * @param url 静的 GTFS の ZIP URL
     * @param onProgress 進捗コールバック (0.0..1.0, メッセージ)
     */
    suspend fun importStaticGtfs(
        feedId: String,
        url: String,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        // 1. ダウンロード
        onProgress(0.05f, "GTFS をダウンロード中...")
        val zipFile = File(context.cacheDir, "gtfs_$feedId.zip")
        downloadFile(url, zipFile)

        // 2. 展開して CSV を読み込み
        onProgress(0.15f, "GTFS を展開中...")
        val extractedDir = File(context.cacheDir, "gtfs_$feedId")
        if (extractedDir.exists()) extractedDir.deleteRecursively()
        extractedDir.mkdirs()
        unzip(zipFile, extractedDir)

        // 3. フィード情報を登録
        val feedInfo = GtfsCsvReader.parse(readCsvFile(extractedDir, "feed_info.txt"))
        val feedInfoRow = feedInfo.firstOrNull()
        database.feedDao().upsert(
            FeedEntity(
                feedId = feedId,
                name = feedInfoRow?.get("feed_publisher_name") ?: feedId,
                staticGtfsUrl = url,
                tripUpdateUrl = null,
                vehiclePositionUrl = null,
                alertUrl = null
            )
        )

        // 4. 各 CSV をパースして DB に保存
        fun readCsv(name: String): String {
            val f = File(extractedDir, name)
            return if (f.exists()) f.readText(Charsets.UTF_8) else ""
        }

        // stops
        onProgress(0.25f, "停留所データを読み込み中...")
        val stops = GtfsParser.parseStops(feedId, readCsv("stops.txt"))
        database.stopDao().deleteByFeed(feedId)
        stops.chunked(1000).forEach { database.stopDao().upsertAll(it) }

        // routes
        onProgress(0.4f, "路線データを読み込み中...")
        val routes = GtfsParser.parseRoutes(feedId, readCsv("routes.txt"))
        database.routeDao().deleteByFeed(feedId)
        routes.chunked(1000).forEach { database.routeDao().upsertAll(it) }

        // trips
        onProgress(0.55f, "便データを読み込み中...")
        val trips = GtfsParser.parseTrips(feedId, readCsv("trips.txt"))
        database.tripDao().deleteByFeed(feedId)
        trips.chunked(1000).forEach { database.tripDao().upsertAll(it) }

        // stop_times（大量データのためバッチ処理）
        // TODO: メモリ効率のためストリーミングパースに改善する（現在は全体を読み込み）
        onProgress(0.7f, "時刻表データを読み込み中...")
        database.stopTimeDao().deleteByFeed(feedId)
        val stopTimesFile = File(extractedDir, "stop_times.txt")
        if (stopTimesFile.exists()) {
            val batchSize = 2000
            val stopTimesText = stopTimesFile.readText(Charsets.UTF_8)
            val stopTimes = GtfsParser.parseStopTimes(feedId, stopTimesText)
            stopTimes.chunked(batchSize).forEach { database.stopTimeDao().upsertAll(it) }
        }

        // calendar
        onProgress(0.85f, "運行日データを読み込み中...")
        val calendarFile = File(extractedDir, "calendar.txt")
        if (calendarFile.exists()) {
            database.calendarDao().deleteCalendarByFeed(feedId)
            val calendar = GtfsParser.parseCalendar(feedId, calendarFile.readText(Charsets.UTF_8))
            calendar.chunked(1000).forEach { database.calendarDao().upsertCalendar(it) }
        }
        val calendarDatesFile = File(extractedDir, "calendar_dates.txt")
        if (calendarDatesFile.exists()) {
            database.calendarDao().deleteCalendarDatesByFeed(feedId)
            val calendarDates = GtfsParser.parseCalendarDates(feedId, calendarDatesFile.readText(Charsets.UTF_8))
            calendarDates.chunked(1000).forEach { database.calendarDao().upsertCalendarDates(it) }
        }

        // 5. クリーンアップ
        onProgress(0.95f, "クリーンアップ中...")
        extractedDir.deleteRecursively()
        zipFile.delete()

        database.feedDao().updateLastStaticUpdate(feedId, System.currentTimeMillis())
        onProgress(1.0f, "完了")
    }

    private fun downloadFile(url: String, dest: File) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body!!.byteStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        zis.copyTo(output)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun readCsvFile(dir: File, name: String): String {
        val f = File(dir, name)
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }
}