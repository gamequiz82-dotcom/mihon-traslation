package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.domain.translation.TranslationPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Translates one downloaded chapter and keeps the generated pages beside its originals. */
class TranslationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph: AppGraph = context.metroGraph()

    @Inject lateinit var downloadManager: DownloadManager
    @Inject lateinit var downloadProvider: DownloadProvider
    @Inject lateinit var mangaRepository: MangaRepository
    @Inject lateinit var chapterRepository: ChapterRepository
    @Inject lateinit var sourceManager: SourceManager
    @Inject lateinit var preferences: TranslationPreferences
    @Inject lateinit var networkHelper: eu.kanade.tachiyomi.network.NetworkHelper

    init { graph.inject(this) }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mangaId = inputData.getLong(KEY_MANGA_ID, -1)
        val chapterId = inputData.getLong(KEY_CHAPTER_ID, -1)
        val apiKey = preferences.apiKey.get().trim()
        if (mangaId < 0 || chapterId < 0 || apiKey.isEmpty()) return@withContext Result.failure()
        try {
            setForeground(getForegroundInfo())
            val manga = mangaRepository.getMangaById(mangaId)
            val chapter = chapterRepository.getChapterById(chapterId) ?: return@withContext Result.failure()
            val source = sourceManager.getOrStub(manga.source)
            if (!downloadManager.isChapterDownloadedOnDisk(chapter.name, chapter.scanlator, chapter.url, manga.title, source)) {
                downloadManager.downloadChapters(manga, listOf(chapter))
                var waitSeconds = 0
                while (!downloadManager.isChapterDownloadedOnDisk(chapter.name, chapter.scanlator, chapter.url, manga.title, source)) {
                    coroutineContext.ensureActive()
                    delay(1_000)
                    waitSeconds++
                    if (waitSeconds >= 900) return@withContext Result.retry()
                    if (waitSeconds % 10 == 0) setForeground(foregroundInfo(0, 0, "بانتظار اكتمال تنزيل الفصل"))
                }
            }
            val chapterDir = downloadProvider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)
                ?: return@withContext Result.failure()
            if (!chapterDir.isDirectory) return@withContext Result.failure()
            val pages = downloadManager.buildPageList(source, manga, chapter)
            val translatedDir = chapterDir.findFile(TRANSLATED_DIR) ?: chapterDir.createDirectory(TRANSLATED_DIR)
                ?: return@withContext Result.failure()
            pages.forEachIndexed { index, page ->
                coroutineContext.ensureActive()
                val filename = String.format(Locale.ROOT, "%05d.png", index + 1)
                if (translatedDir.findFile(filename) == null) {
                    val uri = page.uri ?: error("Downloaded page URI is missing")
                    val original = applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read downloaded page")
                    val regions = detectAndTranslate(original, apiKey, preferences.targetLanguage.get())
                    val translated = render(original, regions)
                    translatedDir.createFile(filename)?.openOutputStream()?.use { it.write(translated) }
                        ?: error("Could not save translated page")
                }
                setProgress(workDataOf(KEY_PROGRESS to ((index + 1) * 100 / pages.size), KEY_PAGE to (index + 1), KEY_TOTAL to pages.size))
                setForeground(foregroundInfo(index + 1, pages.size))
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to (e.message ?: "Translation failed")))
        }
    }

    private fun foregroundInfo(page: Int, total: Int, status: String? = null): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle("ترجمة الفصل")
            setContentText(status ?: if (total > 0) "معالجة الصفحة $page من $total" else "جارٍ تجهيز الصفحات")
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            if (total > 0) setProgress(total, page, false)
        }.build()
        return ForegroundInfo(
            30000 + inputData.getLong(KEY_CHAPTER_ID, 0).rem(10000).toInt(),
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private suspend fun detectAndTranslate(image: ByteArray, apiKey: String, language: String): List<Region> {
        val encoded = android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP)
        val prompt = "Find each speech bubble's text. Translate every text to $language. Return only JSON: {\"regions\":[{\"x\":0,\"y\":0,\"w\":100,\"h\":50,\"text\":\"translation\"}]}. Coordinates are normalized 0..1000 from top-left; include only speech bubbles and preserve reading order."
        val payload = JSONObject().put("model", "gpt-4o-mini").put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", org.json.JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,$encoded"))))))
        val request = Request.Builder().url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val body = withContext(Dispatchers.IO) {
            networkHelper.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Translation service returned HTTP ${response.code}")
                response.body?.string() ?: error("Translation service returned an empty response")
            }
        }
        val content = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        val json = JSONObject(content)
        return buildList {
            val array = json.optJSONArray("regions") ?: return@buildList
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(Region(item.optInt("x"), item.optInt("y"), item.optInt("w"), item.optInt("h"), item.optString("text")))
            }
        }
    }

    private fun render(bytes: ByteArray, regions: List<Region>): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Unsupported image")
        val canvas = Canvas(bitmap)
        regions.forEach { region ->
            val left = bitmap.width * region.x.coerceIn(0, 1000) / 1000f
            val top = bitmap.height * region.y.coerceIn(0, 1000) / 1000f
            val right = bitmap.width * (region.x + region.w).coerceIn(0, 1000) / 1000f
            val bottom = bitmap.height * (region.y + region.h).coerceIn(0, 1000) / 1000f
            val box = RectF(left, top, right, bottom)
            canvas.drawOval(box, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                textSize = (box.height() / 5f).coerceIn(12f, 42f)
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
            val layout = StaticLayout.Builder.obtain(region.text, 0, region.text.length, textPaint, box.width().toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(if (preferences.targetLanguage.get().equals("Arabic", true)) android.text.TextDirectionHeuristics.RTL else android.text.TextDirectionHeuristics.LTR)
                .setMaxLines(5)
                .build()
            canvas.save()
            canvas.translate(box.left, box.top + (box.height() - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }

    private data class Region(val x: Int, val y: Int, val w: Int, val h: Int, val text: String)

    companion object {
        const val KEY_MANGA_ID = "manga_id"
        const val KEY_CHAPTER_ID = "chapter_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_PAGE = "page"
        const val KEY_TOTAL = "total"
        const val TRANSLATED_DIR = "translated-pages"
    }
}
