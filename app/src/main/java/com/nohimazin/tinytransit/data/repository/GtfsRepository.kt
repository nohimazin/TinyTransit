package com.nohimazin.tinytransit.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
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

class GtfsRepository(
    private val context: Context,
    private val database: TinyTransitDatabase,
    private val httpClient: OkHttpClient
) {
    suspend fun importStaticGtfs(
        feedId: String,
        url: String,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        // 1. Download
        onProgress(0.05f, "Downloading GTFS...")
        val zipFile = File(context.cacheDir, "gtfs_$feedId.zip")
        downloadFile(url, zipFile)

        // 2. Extract
        onProgress(0.1f, "Extracting GTFS...")
        val extractedDir = File(context.cacheDir, "gtfs_$feedId")
        if (extractedDir.exists()) extractedDir.deleteRecursively()
        extractedDir.mkdirs()
        unzip(zipFile, extractedDir)

        fun readCsv(name: String): String {
            val f = File(extractedDir, name)
            return if (f.exists()) f.readText(Charsets.UTF_8) else ""
        }

        // 3. Parse small CSVs
        onProgress(0.15f, "Parsing stops...")
        val stops = GtfsParser.parseStops(feedId, readCsv("stops.txt"))

        onProgress(0.2f, "Parsing routes...")
        val routes = GtfsParser.parseRoutes(feedId, readCsv("routes.txt"))

        onProgress(0.25f, "Parsing trips...")
        val trips = GtfsParser.parseTrips(feedId, readCsv("trips.txt"))

        onProgress(0.3f, "Parsing calendar...")
        val calendarFile = File(extractedDir, "calendar.txt")
        val calendar = if (calendarFile.exists()) {
            GtfsParser.parseCalendar(feedId, calendarFile.readText(Charsets.UTF_8))
        } else emptyList()
        val calendarDatesFile = File(extractedDir, "calendar_dates.txt")
        val calendarDates = if (calendarDatesFile.exists()) {
            GtfsParser.parseCalendarDates(feedId, calendarDatesFile.readText(Charsets.UTF_8))
        } else emptyList()

        val feedInfo = GtfsCsvReader.parse(readCsv("feed_info.txt"))
        val feedInfoRow = feedInfo.firstOrNull()

        // agency.txt からタイムゾーンを取得
        val agencyRows = GtfsCsvReader.parse(readCsv("agency.txt"))
        val agencyTimezone = agencyRows.firstOrNull()?.get("agency_timezone")

        // 4a. stop_times: bulk insert via multi-row INSERT in its own transaction
        onProgress(0.35f, "Loading stop_times...")
        val stopTimesFile = File(extractedDir, "stop_times.txt")
        var stopTimeCount = 0
        if (stopTimesFile.exists()) {
            val db = database.openHelper.writableDatabase
            try { db.execSQL("PRAGMA synchronous = OFF") } catch (e: Exception) { android.util.Log.w("GtfsImport", "synchronous pragma failed", e) }
            // ジャーナルを無効化してバルク削除・挿入を高速化（クラッシュ時は再インポートで復旧）
            db.beginTransaction()

            try {
                // テーブルを DROP & CREATE して高速にクリア（DELETE は 55 万行で数十秒かかる）
                val createTableSql = db.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='stop_times'").use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                val indexSqls = mutableListOf<String>()
                db.query("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='stop_times' AND sql IS NOT NULL").use { c ->
                    while (c.moveToNext()) indexSqls.add(c.getString(0))
                }
                if (createTableSql != null) {
                    db.execSQL("DROP TABLE IF EXISTS stop_times")
                    db.execSQL(createTableSql)
                    for (sql in indexSqls) db.execSQL(sql)

                } else {
                    val deleted = db.delete("stop_times", "feedId = ?", arrayOf(feedId))

                }
                val sqlPrefix = "INSERT INTO stop_times (feedId,tripId,stopId,stopSequence,arrivalTime,departureTime,stopHeadsign,pickupType,dropOffType) VALUES "
                val tuple = "(?,?,?,?,?,?,?,?,?)"
                val batchRows = ArrayList<Array<Any?>>(100)
                stopTimesFile.bufferedReader(Charsets.UTF_8).use { reader ->
                    val headerLine = reader.readLine() ?: return@use
                    val header = GtfsCsvReader.parseLine(headerLine)
                    val idx = HashMap<String, Int>(header.size)
                    for (i in header.indices) idx[header[i]] = i
                    val iTrip = idx["trip_id"] ?: 0
                    val iArr = idx["arrival_time"]
                    val iDep = idx["departure_time"]
                    val iStop = idx["stop_id"] ?: 1
                    val iSeq = idx["stop_sequence"] ?: 2
                    val iHead = idx["stop_headsign"]
                    val iPick = idx["pickup_type"]
                    val iDrop = idx["drop_off_type"]

                    fun flushBatch() {
                        if (batchRows.isEmpty()) return
                        val placeholders = batchRows.joinToString(",") { tuple }
                        val stmt = db.compileStatement(sqlPrefix + placeholders)
                        var bindIdx = 1
                        for (row in batchRows) {
                            for (v in row) {
                                when (v) {
                                    null -> stmt.bindNull(bindIdx)
                                    is String -> stmt.bindString(bindIdx, v)
                                    is Long -> stmt.bindLong(bindIdx, v)
                                }
                                bindIdx++
                            }
                        }
                        stmt.execute()
                        stmt.close()
                        batchRows.clear()
                    }

                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.isEmpty()) continue
                        val f = GtfsCsvReader.parseLine(line!!)
                        val tripId = f.getOrElse(iTrip) { "" }
                        if (tripId.isEmpty()) continue
                        val arr = iArr?.let { f.getOrElse(it) { "" } }?.takeIf { it.isNotEmpty() }
                        val dep = iDep?.let { f.getOrElse(it) { "" } }?.takeIf { it.isNotEmpty() }
                        val head = iHead?.let { f.getOrElse(it) { "" } }?.takeIf { it.isNotEmpty() }
                        val pick = iPick?.let { f.getOrElse(it) { "" }.toIntOrNull() }
                        val drop = iDrop?.let { f.getOrElse(it) { "" }.toIntOrNull() }
                        batchRows.add(arrayOf<Any?>(
                            feedId,
                            tripId,
                            f.getOrElse(iStop) { "" },
                            (f.getOrElse(iSeq) { "" }.toIntOrNull() ?: 0).toLong(),
                            arr,
                            dep,
                            head,
                            pick?.toLong(),
                            drop?.toLong()
                        ))
                        stopTimeCount++
                        if (batchRows.size >= 100) {
                            flushBatch()
                            if (stopTimeCount % 100000 == 0) {
                                onProgress(0.35f + (stopTimeCount / 558816f) * 0.35f, "Loading stop_times... ($stopTimeCount)")
                            }
                        }
                    }
                    flushBatch()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            // journal_mode restore skipped (pragma not supported on this connection)
        }

        // 4b. Other tables in Room transaction
        onProgress(0.75f, "Registering to database...")
        database.withTransaction {
            database.feedDao().upsert(
                FeedEntity(
                    feedId = feedId,
                    name = feedInfoRow?.get("feed_publisher_name") ?: feedId,
                    agencyTimezone = agencyTimezone,
                    staticGtfsUrl = url,
                    tripUpdateUrl = null,
                    vehiclePositionUrl = null,
                    alertUrl = null
                )
            )
            database.stopDao().deleteByFeed(feedId)
            stops.chunked(1000).forEach { database.stopDao().upsertAll(it) }

            database.routeDao().deleteByFeed(feedId)
            routes.chunked(1000).forEach { database.routeDao().upsertAll(it) }

            database.tripDao().deleteByFeed(feedId)
            trips.chunked(1000).forEach { database.tripDao().upsertAll(it) }

            database.calendarDao().deleteCalendarByFeed(feedId)
            database.calendarDao().deleteCalendarDatesByFeed(feedId)
            calendar.chunked(1000).forEach { database.calendarDao().upsertCalendar(it) }
            calendarDates.chunked(1000).forEach { database.calendarDao().upsertCalendarDates(it) }
        }

        // 5. Cleanup
        onProgress(0.95f, "Cleaning up...")
        extractedDir.deleteRecursively()
        zipFile.delete()

        database.feedDao().updateLastStaticUpdate(feedId, System.currentTimeMillis())
        onProgress(1.0f, "Import complete")
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

    companion object {
        const val DEFAULT_FEED_ID = "sankobus"

        /**
         * 停留所 ID から基底 ID を取得します。
         * 例: "100393_1" -> "100393", "100393_2" -> "100393"
         * アンダースコア後の数字は進行方向を表すため、統合して扱います。
         */
        fun baseStopId(stopId: String): String {
            val idx = stopId.lastIndexOf('_')
            if (idx > 0 && idx < stopId.length - 1) {
                val suffix = stopId.substring(idx + 1)
                if (suffix.all { it.isDigit() }) {
                    return stopId.substring(0, idx)
                }
            }
            return stopId
        }

        /**
         * 停留所 ID の方向サフィックスを取得します。
         * 例: "100393_1" -> "1", "100393" -> ""
         */
        fun stopDirection(stopId: String): String {
            val idx = stopId.lastIndexOf('_')
            if (idx > 0 && idx < stopId.length - 1) {
                val suffix = stopId.substring(idx + 1)
                if (suffix.all { it.isDigit() }) {
                    return suffix
                }
            }
            return ""
        }
    }

    /**
     * 停留所名で検索します。
     * アンダースコア後の数字だけが異なる停留所は統合して 1 件にまとめます。
     */
    /**
     * 監視対象停留所一覧を取得します。
     */
    suspend fun getMonitoredStops(feedId: String): List<com.nohimazin.tinytransit.data.db.entity.MonitoredStopEntity> =
        withContext(Dispatchers.IO) {
            database.monitoredStopDao().getAll(feedId)
        }

    /**
     * 監視対象経路一覧を取得します。
     */
    suspend fun getMonitoredRoutes(feedId: String): List<com.nohimazin.tinytransit.data.db.entity.MonitoredRouteEntity> =
        withContext(Dispatchers.IO) {
            database.monitoredRouteDao().getAll(feedId)
        }

    /**
     * 登録済み停留所数を取得します。
     */
    suspend fun getStopCount(feedId: String): Int = withContext(Dispatchers.IO) {
        database.stopDao().count(feedId)
    }

    suspend fun searchStops(feedId: String, query: String): List<com.nohimazin.tinytransit.data.db.entity.StopEntity> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val results = database.stopDao().searchByName(feedId, query, 50)
            // 基底 ID でグループ化し、各グループの最初の 1 件を返す
            val seen = mutableSetOf<String>()
            results.filter { stop ->
                val base = GtfsRepository.baseStopId(stop.stopId)
                if (base in seen) false else { seen.add(base); true }
            }
        }

    /**
     * 基底停留所 ID に紐づく全方向の停留所を取得します。
     */
    suspend fun getStopVariants(feedId: String, baseStopId: String): List<com.nohimazin.tinytransit.data.db.entity.StopEntity> =
        withContext(Dispatchers.IO) {
            database.stopDao().getAll(feedId).filter { GtfsRepository.baseStopId(it.stopId) == baseStopId }
        }

    /**
     * 乗車停留所と降車停留所を経由する便を検索します。
     * 両方の停留所を通過する便の trip_id を返します。
     */
    suspend fun findTripsBetweenStops(
        feedId: String,
        originStopId: String,
        destinationStopId: String
    ): List<String> = withContext(Dispatchers.IO) {
        val originBase = baseStopId(originStopId)
        val destBase = baseStopId(destinationStopId)
        val originVariants = getStopVariants(feedId, originBase).map { it.stopId }.toSet()
        val destVariants = getStopVariants(feedId, destBase).map { it.stopId }.toSet()

        // 乗車停留所の stop_times から trip_id を取得
        val originTrips = originVariants.flatMap { v ->
            database.stopTimeDao().getByStop(feedId, v)
        }.map { it.tripId }.distinct()

        // 降車停留所の stop_times から trip_id を取得
        val destTrips = destVariants.flatMap { v ->
            database.stopTimeDao().getByStop(feedId, v)
        }.map { it.tripId }.toSet()

        // 両方を経由する便
        originTrips.filter { it in destTrips }
    }

    /**
     * 指定された停留所に乗り入れる路線一覧を取得します。
     */
    suspend fun getRoutesForStop(feedId: String, stopId: String): List<com.nohimazin.tinytransit.data.db.entity.RouteEntity> =
        withContext(Dispatchers.IO) {
            val baseId = baseStopId(stopId)
            val variants = getStopVariants(feedId, baseId)
            val allStopTimes = variants.flatMap { v -> database.stopTimeDao().getByStop(feedId, v.stopId) }
            val tripIds = allStopTimes.map { it.tripId }.distinct()
            val routeIds = tripIds.mapNotNull { tid ->
                database.tripDao().get(feedId, tid)?.routeId
            }.distinct()
            routeIds.mapNotNull { rid -> database.routeDao().get(feedId, rid) }
        }

    suspend fun verifyDataIntegrity(feedId: String): com.nohimazin.tinytransit.data.verification.GtfsVerificationResult =
        withContext(Dispatchers.IO) {
            val stopCount = database.stopDao().count(feedId)
            val routes = database.routeDao().getAll(feedId)
            val routeIds = routes.map { it.routeId }.toSet()

            val allTrips = mutableListOf<com.nohimazin.tinytransit.data.db.entity.TripEntity>()
            for (r in routes) {
                allTrips.addAll(database.tripDao().getByRoute(feedId, r.routeId))
            }
            val tripIds = allTrips.map { it.tripId }.toSet()
            val serviceIds = allTrips.map { it.serviceId }.toSet()

            val allStopTimes = mutableListOf<com.nohimazin.tinytransit.data.db.entity.StopTimeEntity>()
            for (t in allTrips) {
                allStopTimes.addAll(database.stopTimeDao().getByTrip(feedId, t.tripId))
            }
            val stopIds = database.stopDao().searchByName(feedId, "", 100000).map { it.stopId }.toSet()

            val calendarCount = serviceIds.count { sid ->
                database.calendarDao().getCalendar(feedId, sid) != null
            }
            val calendarDateCount = serviceIds.sumOf { sid ->
                database.calendarDao().getCalendarDates(feedId, sid).size
            }

            val orphanTrips = allTrips.count { it.routeId !in routeIds }
            val orphanStopTimesTrip = allStopTimes.count { it.tripId !in tripIds }
            val orphanStopTimesStop = allStopTimes.count { it.stopId !in stopIds }
            val tripsWithoutService = allTrips.count {
                it.serviceId !in serviceIds || database.calendarDao().getCalendar(feedId, it.serviceId) == null
            }

            com.nohimazin.tinytransit.data.verification.GtfsVerificationResult(
                feedId = feedId,
                stopCount = stopCount,
                routeCount = routes.size,
                tripCount = allTrips.size,
                stopTimeCount = allStopTimes.size,
                calendarCount = calendarCount,
                calendarDateCount = calendarDateCount,
                orphanTripsWithoutRoute = orphanTrips,
                orphanStopTimesWithoutTrip = orphanStopTimesTrip,
                orphanStopTimesWithoutStop = orphanStopTimesStop,
                tripsWithoutService = tripsWithoutService,
                isConsistent = orphanTrips == 0 && orphanStopTimesTrip == 0 && orphanStopTimesStop == 0
            )
        }
}