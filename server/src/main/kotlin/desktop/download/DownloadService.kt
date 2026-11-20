package desktop.download

import desktop.api.Catalog
import desktop.api.DownloadRequest
import desktop.api.DownloadTaskDto
import desktop.api.SeasonDownloadRequest
import desktop.auth.Auth
import desktop.links.ClientStreams
import desktop.links.LinkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class DownloadTask(
    val id: String,
    val provider: String,
    val title: String,
    val year: Int?,
    val kind: String,
    val season: Int?,
    val episode: Int?,
    val episodeName: String?,
    val data: String,
    val addedAt: Long = System.currentTimeMillis(),
) {
    @Volatile var status: String = "queued" // queued, resolving, downloading, completed, failed, cancelled
    @Volatile var progress: Double = 0.0
    @Volatile var speed: String? = null
    @Volatile var eta: String? = null
    @Volatile var filePath: String? = null
    @Volatile var error: String? = null
    @Volatile var process: Process? = null

    fun dto() = DownloadTaskDto(
        id = id,
        title = title,
        kind = kind,
        season = season,
        episode = episode,
        episodeName = episodeName,
        status = status,
        progress = progress,
        speed = speed,
        eta = eta,
        filePath = filePath,
        error = error,
        addedAt = addedAt,
    )
}

class DownloadService(
    private val scope: CoroutineScope,
    private val catalog: Catalog,
    private val links: LinkService,
    private val clientStreams: ClientStreams,
    private val port: Int,
) {
    private val log = LoggerFactory.getLogger(DownloadService::class.java)
    private val queue = ConcurrentLinkedQueue<DownloadTask>()
    private val allTasks = ConcurrentHashMap<String, DownloadTask>()
    private val activeWorkerRunning = AtomicBoolean(false)
    private var workerJob: Job? = null

    val moviesDir: File
        get() = File(System.getenv("MOVIES_PATH") ?: "/data/Media/Movies")
    val showsDir: File
        get() = File(System.getenv("SHOWS_PATH") ?: "/data/Media/Shows")

    init {
        startWorker()
    }

    private fun startWorker() {
        workerJob?.cancel()
        workerJob = scope.launch(Dispatchers.IO) {
            while (true) {
                val task = queue.poll()
                if (task == null) {
                    delay(1000)
                    continue
                }
                if (task.status == "cancelled") continue
                try {
                    processTask(task)
                } catch (e: Exception) {
                    log.error("Failed processing download task ${task.id}", e)
                    task.status = "failed"
                    task.error = e.message ?: "Unknown error"
                }
            }
        }
    }

    private fun cleanFilename(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().ifEmpty { "Media" }

    private suspend fun processTask(task: DownloadTask) {
        task.status = "resolving"
        log.info("Resolving download link for task ${task.id}: ${task.title}")

        val provider = catalog.provider(task.provider, true)
        val linkSet = links.resolve(provider, task.data, fresh = false)

        // Wait up to 10 seconds for links to resolve
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !linkSet.done) {
            delay(500)
            if (linkSet.links.any { it.playable && it.link.quality >= 1080 }) break
        }

        val playableLinks = linkSet.links.filter { it.playable }.sortedByDescending { it.link.quality }
        if (playableLinks.isEmpty()) {
            task.status = "failed"
            task.error = linkSet.error ?: "No playable links found"
            log.warn("No playable links found for ${task.title}")
            return
        }

        val best = playableLinks.first()
        log.info("Selected stream '${best.link.name}' quality=${best.link.quality} for ${task.title}")

        val streamDto = clientStreams.stream(best.id, direct = false, proxyAll = false)
        val streamUrl = if (streamDto.proxied && streamDto.url.startsWith("/")) {
            "http://127.0.0.1:$port${streamDto.url}"
        } else {
            streamDto.url
        }

        val cleanTitle = cleanFilename(task.title)
        val targetFile: File
        if (task.kind == "movie") {
            val yearPart = if (task.year != null && task.year > 0) " (${task.year})" else ""
            val dir = File(moviesDir, "$cleanTitle$yearPart")
            dir.mkdirs()
            targetFile = File(dir, "$cleanTitle$yearPart.mp4")
        } else {
            val s = task.season ?: 1
            val e = task.episode ?: 1
            val sStr = String.format("%02d", s)
            val eStr = String.format("%02d", e)
            val dir = File(showsDir, "$cleanTitle/Season $sStr")
            dir.mkdirs()
            targetFile = File(dir, "$cleanTitle - S${sStr}E${eStr}.mp4")
        }

        task.filePath = targetFile.absolutePath
        task.status = "downloading"
        task.progress = 0.0

        runDownload(task, streamUrl, targetFile, streamDto.proxied, streamDto.headers)
    }

    private suspend fun runDownload(
        task: DownloadTask,
        streamUrl: String,
        targetFile: File,
        proxied: Boolean,
        headers: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val ytDlpBin = findBinary("yt-dlp") ?: "yt-dlp"

        val cmd = mutableListOf(
            ytDlpBin,
            "--concurrent-fragments", "5",
            "--no-part",
            "--retries", "10",
            "--newline",
            "--progress",
        )

        if (proxied) {
            cmd.add("--add-header")
            cmd.add("${Auth.INTERNAL_HEADER}: ${Auth.INTERNAL_TOKEN}")
        } else {
            for ((k, v) in headers) {
                cmd.add("--add-header")
                cmd.add("$k: $v")
            }
        }

        cmd.add("-o")
        cmd.add(targetFile.absolutePath)
        cmd.add(streamUrl)

        log.info("Running: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        task.process = proc

        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        var line: String?
        val progressRegex = Regex("([0-9.]+)%")
        val speedRegex = Regex("at\\s+([0-9.]+[A-Za-z/]+)")
        val etaRegex = Regex("ETA\\s+([0-9:]+)")

        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            progressRegex.find(l)?.let { m ->
                task.progress = m.groupValues[1].toDoubleOrNull() ?: task.progress
            }
            speedRegex.find(l)?.let { m ->
                task.speed = m.groupValues[1]
            }
            etaRegex.find(l)?.let { m ->
                task.eta = m.groupValues[1]
            }
        }

        val code = proc.waitFor()
        task.process = null

        if (task.status == "cancelled") {
            log.info("Task ${task.id} was cancelled")
            targetFile.delete()
            return@withContext
        }

        if (code == 0) {
            task.status = "completed"
            task.progress = 100.0
            task.speed = null
            task.eta = null
            log.info("Completed download for ${task.title} -> ${targetFile.absolutePath}")
            notifyJellyfin()
        } else {
            task.status = "failed"
            task.error = "yt-dlp exited with code $code"
            log.error("yt-dlp failed with code $code for ${task.title}")
        }
    }

    private fun findBinary(name: String): String? {
        val paths = listOf("/usr/local/bin/$name", "/usr/bin/$name", "/bin/$name")
        return paths.firstOrNull { File(it).canExecute() }
    }

    private fun notifyJellyfin() {
        val jfUrl = System.getenv("JELLYFIN_URL")?.trimEnd('/')
        val jfKey = System.getenv("JELLYFIN_API_KEY")
        if (jfUrl.isNullOrBlank() || jfKey.isNullOrBlank()) return

        scope.launch(Dispatchers.IO) {
            try {
                val conn = URL("$jfUrl/Library/Refresh?api_key=$jfKey").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.responseCode
                conn.disconnect()
                log.info("Triggered Jellyfin library refresh")
            } catch (e: Exception) {
                log.warn("Failed to notify Jellyfin: ${e.message}")
            }
        }
    }

    fun queue(req: DownloadRequest): DownloadTaskDto {
        val id = UUID.randomUUID().toString()
        val task = DownloadTask(
            id = id,
            provider = req.provider,
            title = req.title,
            year = req.year,
            kind = req.kind,
            season = req.season,
            episode = req.episode,
            episodeName = req.episodeName,
            data = req.data,
        )
        allTasks[id] = task
        queue.add(task)
        log.info("Queued download task $id: ${task.title} (S${task.season}E${task.episode})")
        return task.dto()
    }

    suspend fun queueSeason(req: SeasonDownloadRequest): List<DownloadTaskDto> {
        val details = catalog.details(req.provider, req.url, true)
        val episodes = details.episodes.filter { (it.season ?: 1) == req.season }
        val results = mutableListOf<DownloadTaskDto>()

        for ((index, ep) in episodes.withIndex()) {
            val id = UUID.randomUUID().toString()
            val task = DownloadTask(
                id = id,
                provider = req.provider,
                title = details.name,
                year = details.year,
                kind = "series",
                season = ep.season ?: req.season,
                episode = ep.episode ?: (index + 1),
                episodeName = ep.name,
                data = ep.data,
            )
            allTasks[id] = task
            queue.add(task)
            results.add(task.dto())
        }
        log.info("Queued entire season ${req.season} for '${details.name}': ${results.size} episodes")
        return results
    }

    fun tasks(): List<DownloadTaskDto> =
        allTasks.values.sortedByDescending { it.addedAt }.map { it.dto() }

    fun cancel(id: String): Boolean {
        val task = allTasks[id] ?: return false
        task.status = "cancelled"
        task.process?.destroy()
        queue.remove(task)
        return true
    }

    fun clearCompleted() {
        val toRemove = allTasks.filter { (_, t) -> t.status in setOf("completed", "failed", "cancelled") }.keys
        toRemove.forEach { allTasks.remove(it) }
    }
}
