package eu.kanade.tachiyomi.extension.zh.lizi

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 栗子漫画（com.hbsclj.uth）mihon 图源。
 *
 * 数据源全部经 Frida 动态取证确认，详见 work/数据源逆向结论.md。
 *
 * - base：`http://ai.xajtl.com`（明文 HTTP，manifest 已开 usesCleartextTraffic）
 * - 信封：`{"code":201|400|401, "data":…, "msg":…}`，**成功码是 201 不是 200**
 * - 匿名可用的接口：rank/list、home/data、search/full、v2/detail/{id}、configv2
 * - `chapter/v3/{id}`（正文）需要 `Authorization`，否则真实 HTTP 401
 * - `lzsign`/`t` 服务端不校验 → 不实现
 * - 图片：JSON 里是相对路径，拼 `https://cdn.lzimg.xyz`，无 Referer 校验
 */
class Lizi : HttpSource(), ConfigurableSource {

    override val name = "栗子漫画"

    override val baseUrl = "http://ai.xajtl.com"

    override val lang = "zh"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 源级偏好存储。
     *
     * 为什么不用 `mihonx.source.utils.sourcePreferences`：
     * extensions-lib 的 `mihonx.*` 是 **compileOnly 桩**（反编译可见方法体就是
     * `throw RuntimeException("Stub!")`），必须由宿主 App 在运行时提供实现。
     * 实测本机 mihon（app.mihon.dev 0.20.4-11）的全部 dex 里 `mihonx` 命中 **0 次**
     * → 用了会在首次请求时 `NoClassDefFoundError: Lmihonx/source/utils/PreferencesKt;` 崩溃。
     *
     * 宿主真正提供的是 `eu.kanade.tachiyomi.source.ConfigurableSource`（含
     * `getSourcePreferences()` 默认实现）与 injekt（`uy.kohesive.injekt:injekt-core`）。
     * 这里直接走 injekt，取到的 SharedPreferences 文件名与宿主 `preferenceKey()`
     * 一致（`source_<id>`），语义等价。
     */
    /** 与宿主 `ConfigurableSource.preferenceKey()` 保持一致（`source_<id>`）。 */
    private val PREF_FILE: String
        get() = "source_$id"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Context>().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------------ 设置项

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_AUTHORIZATION
            title = "Authorization（登录令牌）"
            summary = "粘贴栗子漫画的登录令牌（一长串 eyJ 开头的字符）。留空可正常浏览/搜索/看详情，但打开章节会提示需要登录。"
            dialogTitle = "Authorization"
            dialogMessage = "取法：用已登录的栗子漫画 App 抓一次 /app/api/chapter/v3/… 请求，复制请求头 Authorization 的整段值粘到这里。\n\n注意：栗子服务端只认裸令牌，不要带开头的 “Bearer ”（带了会自动去掉）。"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, _ ->
                cachedHeaders = null
                true
            }
        }.also { screen.addPreference(it) }
    }

    // ------------------------------------------------------------------ 请求头

    /**
     * HttpSource.headers 是 lazy 的，用户在设置里改了 token 不会生效；
     * 所以每次请求都重新构造一份，并且缓存到 token 变化为止。
     */
    @Volatile
    private var cachedHeaders: Headers? = null

    @Volatile
    private var cachedToken: String? = null

    private val authorization: String
        get() = preferences.getString(PREF_AUTHORIZATION, "").orEmpty().trim()

    /**
     * 栗子服务端只认**裸 JWT**。
     *
     * 实测（真实 chapterId 42680）：
     * - `Authorization: <jwt>`        -> 200 / code 201
     * - `Authorization: Bearer <jwt>` -> 401 无权限
     * - 不带该头                       -> 401 无权限
     *
     * 所以这里不加 Bearer 前缀；用户若自己粘了 `Bearer ` 就自动剥掉。
     */
    private fun authHeaderValue(): String? {
        val raw = authorization
        if (raw.isEmpty()) return null
        return raw
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .trim()
            .ifEmpty { null }
    }

    private fun apiHeaders(): Headers {
        val token = authorization
        cachedHeaders?.let { if (cachedToken == token) return it }

        val built = headersBuilder().apply {
            authHeaderValue()?.let { add("Authorization", it) }
        }.build()

        cachedHeaders = built
        cachedToken = token
        return built
    }

    /** 图片走 CDN，不需要也不应该带站点 token。 */
    private fun imageHeaders(): Headers = headersBuilder().build()

    // ------------------------------------------------------------------ 热门

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/app/api/rank/list", apiHeaders())

    override fun popularMangaParse(response: Response): MangasPage {
        val data = dataOf(response)
        val ranks = data["rank_list"]?.jsonArray ?: JsonArray(emptyList())
        val mangas = ranks
            .flatMap { group -> group.jsonObject.comicList() }
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    // ------------------------------------------------------------------ 最新

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/app/api/home/data", apiHeaders())

    override fun latestUpdatesParse(response: Response): MangasPage {
        val data = dataOf(response)
        val sections = data["home_content_list"]?.jsonArray ?: JsonArray(emptyList())
        val mangas = sections
            .flatMap { section -> section.jsonObject.comicList() }
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    // ------------------------------------------------------------------ 搜索

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/app/api/search/full?q=$q", apiHeaders())
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val data = dataOf(response)
        val list = data["search_full"]?.jsonArray ?: JsonArray(emptyList())
        val mangas = list.map { it.jsonObject.toSManga() }.distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    // ------------------------------------------------------------------ 详情

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl/app/api/v2/detail/${manga.url}", apiHeaders())

    override fun mangaDetailsParse(response: Response): SManga =
        dataOf(response).toSManga()

    // ------------------------------------------------------------------ 章节列表

    /** 章节表就在详情接口里，不需要第二个端点。 */
    override fun chapterListRequest(manga: SManga): Request =
        GET("$baseUrl/app/api/v2/detail/${manga.url}", apiHeaders())

    override fun chapterListParse(response: Response): List<SChapter> {
        val detail = dataOf(response)
        val chapters = detail["chapters"]?.jsonArray ?: JsonArray(emptyList())
        return chapters
            .map { it.jsonObject.toSChapter() }
            .sortedBy { it.chapter_number }
    }

    // ------------------------------------------------------------------ 正文

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl/app/api/chapter/v3/${chapter.url}", apiHeaders())

    /**
     * 正文接口未登录时返回的是**真实 HTTP 401**，`asObservableSuccess()` 会在
     * `pageListParse` 之前就抛出 `HttpException(401)`，所以可操作的提示必须在这里给。
     */
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        if (authorization.isEmpty()) {
            return Observable.error(LoginRequiredException())
        }
        return super.fetchPageList(chapter).onErrorResumeNext { error: Throwable ->
            if (error.isUnauthorized()) {
                Observable.error(LoginRequiredException())
            } else {
                Observable.error(error)
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = dataOf(response)
        val pics = data["pics"]?.jsonArray ?: JsonArray(emptyList())
        val picDomain = data["pic_domain"]?.jsonPrimitive?.contentOrNull

        return pics.mapIndexed { index, element ->
            val path = element.jsonPrimitive.contentOrNull.orEmpty()
            Page(index, path, imageUrl(path, picDomain))
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, imageHeaders())

    // ------------------------------------------------------------------ 解析工具

    private fun dataOf(response: Response): JsonObject {
        val root = parseRoot(response)
        when (val code = root["code"]?.jsonPrimitive?.intOrNull) {
            201, 200, null -> Unit
            401 -> throw LoginRequiredException()
            else -> throw IllegalStateException(
                "栗子接口返回 code=$code：${root["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()}",
            )
        }
        return root["data"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private fun parseRoot(response: Response): JsonObject {
        val body = response.body!!.string()
        return runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IllegalStateException("栗子返回了无法解析的内容${responseCodeForLog(response)}", it) }
    }

    /**
     * 取 HTTP 状态码只为打日志，不做分支判断。
     *
     * 为什么不用 `response.code` 直接取：
     * okhttp 3.x 是 `code()` 方法，4.x/5.x 是 `getCode()` 属性，编译期绑死任一写法
     * 在另一版本宿主上都会 `NoSuchMethodError: getCode/code` 崩溃。这里走反射兼容。
     */
    private fun responseCodeForLog(response: Response): String {
        val code = runCatching {
            try {
                response.javaClass.getMethod("getCode").invoke(response)?.toString()
            } catch (_: NoSuchMethodException) {
                response.javaClass.getMethod("code").invoke(response)?.toString()
            }
        }.getOrNull()
        return if (code.isNullOrBlank()) "" else "（HTTP $code）"
    }

    private fun JsonObject.comicList(): List<SManga> =
        (this["comic_list"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonObject.toSManga() }

    private fun JsonObject.toSManga(): SManga = SManga.create().apply {
        val id = this@toSManga["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        url = id
        title = this@toSManga["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        thumbnail_url = imageUrlOrNull(this@toSManga["picY"]?.jsonPrimitive?.contentOrNull)
        author = this@toSManga["author"]?.jsonPrimitive?.contentOrNull
        artist = author
        description = buildDescription(this@toSManga)
        genre = this@toSManga["tags"]?.jsonPrimitive?.contentOrNull
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(", ")
        status = when (this@toSManga["isend"]?.jsonPrimitive?.intOrNull) {
            1 -> SManga.COMPLETED
            0 -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
        // 不在这里设 initialized：列表项标成已初始化会让 mihon 跳过详情请求，
        // 导致简介/标签缺失。交给宿主在 mangaDetailsParse 之后自己置位。
    }

    private fun buildDescription(obj: JsonObject): String {
        val sb = StringBuilder()
        obj["alias"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { sb.append("别名：").append(it.replace(",", " / ")).append('\n') }
        obj["author"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { sb.append("作者：").append(it).append('\n') }
        obj["nums"]?.jsonPrimitive?.intOrNull
            ?.let { sb.append("章节数：").append(it).append('\n') }
        obj["hits"]?.jsonPrimitive?.intOrNull
            ?.let { sb.append("点击：").append(it).append('\n') }
        obj["score"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "0" }
            ?.let { sb.append("评分：").append(it).append('\n') }
        when (obj["isend"]?.jsonPrimitive?.intOrNull) {
            1 -> sb.append("状态：已完结\n")
            0 -> sb.append("状态：连载中\n")
        }
        val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (content.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(content)
        }
        return sb.toString().trim()
    }

    private fun JsonObject.toSChapter(): SChapter = SChapter.create().apply {
        url = this@toSChapter["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        name = this@toSChapter["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        chapter_number = this@toSChapter["order"]?.jsonPrimitive?.intOrNull?.toFloat() ?: -1f
        date_upload = parseIsoDate(this@toSChapter["created_at"]?.jsonPrimitive?.contentOrNull)
        scanlator = null
    }

    /**
     * 判断是否 401。故意不直接引用 `HttpException.code`：
     * 扩展是 compileOnly 绑 extensions-lib 1.6 编译的，宿主（Mihon 旧版 / Komikku /
     * Aniyomi 等衍生版）自带的 `HttpException` 实现版本不一致时，直接访问 `code`
     * 会在运行时抛 `NoSuchMethodError: getCode()`。`asObservableSuccess()` 抛出的
     * 信息固定是 `HTTP error 401`，靠 message 判断即可全版本兼容，反射只做兜底。
     */
    private fun Throwable.isUnauthorized(): Boolean {
        if (message?.contains("401") == true) return true
        if (javaClass.name != "eu.kanade.tachiyomi.network.HttpException") return false
        return runCatching {
            val m = try {
                javaClass.getMethod("getCode")
            } catch (_: NoSuchMethodException) {
                javaClass.getMethod("code")
            }
            (m.invoke(this) as? Int) == 401
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 图片 URL

    private fun imageUrlOrNull(path: String?): String? = path?.let { imageUrl(it, null) }

    private fun imageUrl(path: String, picDomain: String?): String {
        if (path.isBlank()) return ""
        if (path.startsWith("http://") || path.startsWith("https://")) return path

        val base = picDomain
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.trimEnd('/')
            ?: IMAGE_BASE

        return base + if (path.startsWith("/")) path else "/$path"
    }

    private fun parseIsoDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { ISO_FORMAT.get()!!.parse(value)?.time ?: 0L }.getOrDefault(0L)
    }

    private companion object {
        const val PREF_AUTHORIZATION = "authorization"

        /** 图片主线路（备线 http://img.mechat.fun 未启用，主线路实测无 Referer 限制）。 */
        const val IMAGE_BASE = "https://cdn.lzimg.xyz"

        val ISO_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }
    }
}

/**
 * 需要登录才能看正文。文案必须可操作——直接告诉用户去哪儿贴 token。
 */
class LoginRequiredException : IllegalStateException(
    "需要登录才能看正文（栗子服务端返回 401 无权限）。\n\n" +
        "请打开「浏览 → 栗子漫画 → 工具栏 ⋮ → 设置」，把登录令牌粘贴进 Authorization 再重试。\n" +
        "令牌取自已登录的栗子漫画 App，是一长串 eyJ 开头的字符（不要带 “Bearer ” 前缀）。",
)
