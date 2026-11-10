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
            val body = response.body ?: throw IllegalStateException("Empty response body")
            downloadToFile(body.byteStream(), body.contentLength(), dest, MAX_DOWNLOAD_SIZE_BYTES)
        }
    }



    private fun readCsvFile(dir: File, name: String): String {
        val f = File(dir, name)
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    companion object {
        const val MAX_DOWNLOAD_SIZE_BYTES = 200L * 1024 * 1024  // 200 MB (産交バス GTFS ZIP は ~6 MB)

        /**
         * ストリーミングでファイルをダウンロードし、サイズ制限を適用します。
         *
         * - contentLength が既知で上限を超える場合は事前に拒否
         * - 実際の受信バイト数を累積して上限を検証
         * - 上限超過時は部分ファイルを削除
         * - Content-Length と実際の受信量が不一致の場合は拒否
         * - メモリに一括読み込みしない (8KB バッファでストリーミング)
         */
        internal fun downloadToFile(
            input: java.io.InputStream,
            contentLength: Long,
            dest: File,
            maxSizeBytes: Long = MAX_DOWNLOAD_SIZE_BYTES
        ) {
            try {
                if (contentLength >= 0 && contentLength > maxSizeBytes) {
                    throw IllegalStateException("Download exceeds size limit: $contentLength > $maxSizeBytes")
                }
                dest.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        if (total + read > maxSizeBytes) {
                            throw IllegalStateException("Download exceeds size limit")
                        }
                        output.write(buf, 0, read)
                        total += read
                    }
                    if (contentLength >= 0 && total != contentLength) {
                        throw IllegalStateException(
                            "Download size mismatch: expected $contentLength, got $total"
                        )
                    }
                }
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
        }

        const val MAX_ZIP_ENTRIES = 10_000
        const val MAX_ENTRY_SIZE_BYTES = 100L * 1024 * 1024  // 100 MB per entry
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 500L * 1024 * 1024  // 500 MB total

        /**
         * ZIPを安全に展開します。
         *
         * セキュリティ対策:
         * - Zip Slip (パストラバーサル / 絶対パス / ドライブ指定) を拒否
         * - バックスラッシュを含む非標準パスを拒否
         * - エントリー数・個別サイズ・合計サイズの上限で ZIP Bomb を防止
         * - 実際の読み込みバイト数で制限を判定 (申告サイズだけに依存しない)
         * - 重複エントリーによる上書きを防止
         *
         * 制限値は定数として分離しており、テスト時に小さな値を渡して
         * 上限超過を検証できます。
         */
        internal fun unzip(
            zipFile: File,
            destDir: File,
            maxEntries: Int = MAX_ZIP_ENTRIES,
            maxEntrySizeBytes: Long = MAX_ENTRY_SIZE_BYTES,
            maxTotalUncompressedBytes: Long = MAX_TOTAL_UNCOMPRESSED_BYTES
        ) {
            // ZIPマジックナンバーの検証 (PK)
            zipFile.inputStream().use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                if (read < 2 || header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
                    throw IllegalStateException("Not a valid ZIP file: $zipFile")
                }
            }

            val canonicalDestDir = destDir.canonicalFile
            val canonicalRoot = canonicalDestDir.path + File.separator
            val seenPaths = mutableSetOf<String>()
            var entryCount = 0
            var totalUncompressed = 0L

            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > maxEntries) {
                        throw IllegalStateException("ZIP entry count exceeds limit: $maxEntries")
                    }

                    val name = entry.name
                    validateZipEntryName(name)

                    val outFile = File(canonicalDestDir, name).canonicalFile
                    // 最終的な正規パスが展開先配下であることを確認
                    require(
                        outFile.path == canonicalDestDir.path ||
                            outFile.path.startsWith(canonicalRoot)
                    ) { "Invalid ZIP entry path (outside destination): $name" }

                    // 重複エントリーの拒否
                    if (!seenPaths.add(outFile.canonicalPath)) {
                        throw IllegalStateException("Duplicate ZIP entry: $name")
                    }

                    if (entry.isDirectory) {
                        if (!outFile.exists() && !outFile.mkdirs()) {
                            throw IllegalStateException("Failed to create directory: ${outFile.path}")
                        }
                    } else {
                        // 申告サイズが既知で上限超過の場合は事前拒否
                        if (entry.size >= 0 && entry.size > maxEntrySizeBytes) {
                            throw IllegalStateException(
                                "ZIP entry exceeds size limit: $name (${entry.size} > $maxEntrySizeBytes)"
                            )
                        }
                        val parent = outFile.parentFile
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw IllegalStateException("Failed to create parent directory: ${parent.path}")
                        }
                        if (outFile.exists()) {
                            throw IllegalStateException("File already exists (duplicate): ${outFile.path}")
                        }
                        outFile.outputStream().use { output ->
                            totalUncompressed = copyWithLimits(
                                zis, output, maxEntrySizeBytes, totalUncompressed, maxTotalUncompressedBytes
                            )
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        /**
         * ZIPエントリー名を検証します。
         * パストラバーサル、絶対パス、ドライブ指定、UNC、バックスラッシュ、
         * 空名、NUL文字を拒否します。
         */
        internal fun validateZipEntryName(name: String) {
            require(name.isNotEmpty()) { "Empty ZIP entry name" }
            require(!name.contains('\u0000')) { "ZIP entry name contains NUL: $name" }
            require(!name.contains('\\')) { "ZIP entry name contains backslash: $name" }
            require(!name.startsWith("/")) { "Absolute ZIP entry path: $name" }
            if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) {
                throw IllegalArgumentException("Drive letter in ZIP entry path: $name")
            }
            if (name.startsWith("//")) {
                throw IllegalArgumentException("UNC path in ZIP entry: $name")
            }
            val segments = name.split('/')
            for (seg in segments) {
                if (seg == ".." || seg == ".") {
                    throw IllegalArgumentException("Path traversal in ZIP entry: $name")
                }
            }
            require(name.trim('/').isNotEmpty()) { "Empty ZIP entry name: $name" }
        }

        /**
         * 入力をストリーミングでコピーしながら、個別サイズと合計サイズを検証します。
         * 申告サイズだけに依存せず、実際に読み込んだバイト数で判定します。
         */
        internal fun copyWithLimits(
            input: java.io.InputStream,
            output: java.io.OutputStream,
            maxEntrySize: Long,
            totalSoFar: Long,
            maxTotal: Long
        ): Long {
            val buf = ByteArray(8192)
            var entryBytes = 0L
            var total = totalSoFar
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                if (entryBytes + read > maxEntrySize) {
                    throw IllegalStateException("ZIP entry exceeds size limit")
                }
                if (total + read > maxTotal) {
                    throw IllegalStateException("ZIP total uncompressed size exceeds limit")
                }
                output.write(buf, 0, read)
                entryBytes += read
                total += read
            }
            return total
        }

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

        // 降車停留所の stop_times
        val destStopTimes = destVariants.flatMap { v ->
            database.stopTimeDao().getByStop(feedId, v)
        }

        // 乗車停留所の stop_times (最小 stop_sequence を使用)
        val originStopTimes = originVariants.flatMap { v ->
            database.stopTimeDao().getByStop(feedId, v)
        }
        val originSeqMap = originStopTimes.groupBy { it.tripId }
            .mapValues { (_, sts) -> sts.minOf { it.stopSequence } }

        // 両方を経由し、乗車 → 降車の順で停車する便のみ
        destStopTimes.filter { dst ->
            val originSeq = originSeqMap[dst.tripId]
            originSeq != null && originSeq < dst.stopSequence
        }.map { it.tripId }.distinct()
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