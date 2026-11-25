package com.wangzan101.mika.about

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 从一个来源查最新版本。[source] 是镜像前缀(空串表示直连 GitHub),完整请求地址是 `source + 官方地址`。
 *
 * 先问 GitHub API(信息最全,带更新说明、大小和摘要);镜像不支持 API 时退到发布页:
 * 从 `/releases/latest` 的跳转或页面里取最新 tag,再从 `/releases/expanded_assets/<tag>` 读出 APK 文件名和 SHA-256。
 * 直连时保留空发布确认与显式限流冷却语义，避免把无发布或限流误报为普通网络失败。
 *
 * 请求用 OkHttp 的异步接口,响应体读取期间也保持可取消。
 */
internal object UpdateProbe {
    private const val MAX_METADATA_BYTES = 2L * 1024 * 1024

    internal sealed interface ProbeResult {
        data class Found(val release: ReleaseInfo) : ProbeResult
        data object Empty : ProbeResult
        data class RateLimited(val retryAt: Long) : ProbeResult
        data object Failed : ProbeResult
    }

    /** 发布页里的最新版本链接。脚本里拼接出来的 `releases/tag/' + ...` 不会匹配。 */
    private val TAG = Regex("""releases/tag/([A-Za-z0-9._+-]+)""")

    /** 附件行里的 APK 文件名;只取文件名,下载地址统一按官方仓库拼。 */
    private val APK = Regex("""releases/download/[^"'\s<>]+?/([^"'/\s<>]+\.apk)""", RegexOption.IGNORE_CASE)

    private val DIGEST = Regex("""sha256:[0-9a-fA-F]{64}""")
    private val ROW = Regex("<li\\b", RegexOption.IGNORE_CASE)

    suspend fun fetch(
        client: OkHttpClient,
        source: String,
        apiUrl: String,
        releasesUrl: String,
        deviceAbis: List<String>,
        userAgent: String,
    ): ReleaseInfo? = (fetchResult(client, source, apiUrl, releasesUrl, deviceAbis, userAgent) as? ProbeResult.Found)?.release

    suspend fun fetchResult(
        client: OkHttpClient,
        source: String,
        apiUrl: String,
        releasesUrl: String,
        deviceAbis: List<String>,
        userAgent: String,
        now: () -> Long = System::currentTimeMillis,
    ): ProbeResult {
        var apiFailure: Throwable? = null
        val api = try {
            get(client, source + apiUrl, userAgent, json = true, detectRateLimit = source.isEmpty(), now = now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimit) {
            return ProbeResult.RateLimited(e.retryAt)
        } catch (_: MetadataTooLarge) {
            return ProbeResult.Failed
        } catch (e: Exception) {
            apiFailure = e
            null
        }
        if (api != null) {
            try {
                return ProbeResult.Found(Releases.fromGitHub(api, deviceAbis))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (source.isEmpty() && api.isNotBlank()) return ProbeResult.Failed
            }
        }
        if (source.isEmpty() && apiFailure is HttpFailure) {
            if (apiFailure.code == 404) {
                return try {
                    if (confirmsEmptyReleaseChannel(client, apiUrl, userAgent, now)) ProbeResult.Empty else ProbeResult.Failed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RateLimit) {
                    ProbeResult.RateLimited(e.retryAt)
                }
            }
            // 直连时 API 已经给了明确回应(比如普通 403/500):GitHub 是通的,这次就是没查到,不再多打一次网页。
            return ProbeResult.Failed
        }

        val base = releasesUrl.trimEnd('/')
        val tag = try {
            latestTag(client, source + base + "/latest", userAgent)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return ProbeResult.Failed
        val assetsPage = try {
            get(client, source + base + "/expanded_assets/$tag", userAgent, json = false, detectRateLimit = false, now = now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: MetadataTooLarge) {
            return ProbeResult.Failed
        } catch (_: Exception) {
            null
        }
        val assets = assetsPage?.takeIf(String::isNotBlank)?.let { parseAssets(it, tag, base) }
        return ProbeResult.Found(Releases.fromPage(tag, assets, deviceAbis, base))
    }

    /** 404 也可能是仓库不可访问；只有同一仓库的发布列表明确返回 200 + [] 才能确认暂无发布。 */
    private suspend fun confirmsEmptyReleaseChannel(
        client: OkHttpClient,
        apiUrl: String,
        userAgent: String,
        now: () -> Long,
    ): Boolean = try {
        val latest = apiUrl.toHttpUrl()
        val segments = latest.pathSegments
        require(latest.username.isEmpty() && latest.password.isEmpty() && latest.query == null && latest.fragment == null)
        require(segments.size == 5 && segments[0] == "repos" && segments[1].isNotEmpty() && segments[2].isNotEmpty()
            && segments[3] == "releases" && segments[4] == "latest")
        val listUrl = latest.newBuilder().removePathSegment(4).addQueryParameter("per_page", "1").build()
        // 不接受跳往别处的空列表，也不向重定向目的地发送这次确认请求。
        val directClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val list = Json.parseToJsonElement(get(directClient, listUrl.toString(), userAgent, json = true, detectRateLimit = true, now = now))
        list is JsonArray && list.isEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RateLimit) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** 尊重 GitHub 的等待时间；缺少或损坏的响应头至少等待一分钟，避免反复请求加重限流。 */
    private fun rateLimitDeadline(now: () -> Long, reset: String?, retryAfter: String?): Long {
        val current = now()
        val resetAt = reset?.trim()?.toLongOrNull()?.let { runCatching { Math.multiplyExact(it, 1000L) }.getOrNull() }
        val retryAt = retryAfter?.trim()?.let { value ->
            value.toLongOrNull()?.let { seconds ->
                runCatching { Math.addExact(current, Math.multiplyExact(seconds, 1000L)) }.getOrNull()
            } ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        }
        return listOfNotNull(resetAt, retryAt).filter { it > current }.maxOrNull() ?: current + 60_000L
    }

    private val testClient by lazy { UpdateChecker.defaultClient() }

    /** 给「测试镜像」用:整次测试限时,并复用客户端。 */
    suspend fun test(
        prefix: String,
        client: OkHttpClient = testClient,
        apiUrl: String = MikaLinks.LATEST_RELEASE_API,
        releasesUrl: String = MikaLinks.RELEASES,
        timeoutMs: Long = 12_000L,
    ): ReleaseInfo? = withTimeoutOrNull(timeoutMs) {
        try {
            fetch(client, prefix, apiUrl, releasesUrl, emptyList(), "TinyFox")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 最新 tag:有的镜像把 `/releases/latest` 原样跳转,`Location` 里就带着 tag;
     * 有的镜像自己跟到底,返回 200 的发布页,再从页面里找。两种都认。
     */
    private suspend fun latestTag(client: OkHttpClient, url: String, userAgent: String): String? {
        val noRedirect = client.newBuilder().followRedirects(false).build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return execute(noRedirect, request) { resp ->
            if (resp.code in 300..399) resp.header("Location")?.let(::tagIn)
            else if (!resp.isSuccessful) null
            else tagIn(resp.readMetadata())
        }
    }

    private fun tagIn(text: String): String? = TAG.find(text)?.groupValues?.get(1)

    private suspend fun get(
        client: OkHttpClient,
        url: String,
        userAgent: String,
        json: Boolean,
        detectRateLimit: Boolean,
        now: () -> Long,
    ): String {
        val builder = Request.Builder().url(url).header("User-Agent", userAgent)
        if (json) {
            builder.header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
        }
        return execute(client, builder.build()) { resp ->
            if (detectRateLimit && (resp.code == 429 || (resp.code == 403 &&
                    (resp.header("X-RateLimit-Remaining")?.trim() == "0" || resp.header("Retry-After") != null)))) {
                throw RateLimit(rateLimitDeadline(now, resp.header("X-RateLimit-Reset"), resp.header("Retry-After")))
            }
            if (resp.code != 200) throw HttpFailure(resp.code)
            resp.readMetadata()
        }
    }

    /** 已知长度可以提前拒绝，实际读取上限也覆盖分块和解压后的响应。 */
    private fun Response.readMetadata(): String {
        val metadata = body ?: throw IOException("empty body")
        if (metadata.contentLength() > MAX_METADATA_BYTES || metadata.source().request(MAX_METADATA_BYTES + 1)) {
            throw MetadataTooLarge()
        }
        return metadata.string()
    }

    private suspend fun <T> execute(client: OkHttpClient, request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use(read)
                        continuation.resume(result)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }

    /**
     * 从 GitHub 的附件列表网页里读 APK:每一行一个附件,行内有下载路径和 `sha256:...`。
     * 网页结构变了就退回全文找文件名(这时没有摘要,安装前仍会验签名)。
     */
    private fun parseAssets(html: String, tag: String, base: String): List<Releases.ReleaseAsset> {
        val byName = LinkedHashMap<String, String?>()
        for (row in ROW.split(html).drop(1)) {
            val name = APK.find(row)?.groupValues?.get(1) ?: continue
            val digests = DIGEST.findAll(row).map { it.value }.distinct().toList()
            if (name !in byName) byName[name] = digests.singleOrNull()
            if (byName.size >= 32) break
        }
        if (byName.isEmpty()) {
            APK.findAll(html).forEach { match ->
                val name = match.groupValues[1]
                if (name !in byName) byName[name] = null
            }
        }
        return byName.map { (name, digest) ->
            Releases.ReleaseAsset(name, "$base/download/$tag/$name", null, digest)
        }
    }

    private class HttpFailure(val code: Int) : IOException("HTTP $code")
    private class RateLimit(val retryAt: Long) : IOException("GitHub rate limited")
    private class MetadataTooLarge : IOException("update metadata exceeds $MAX_METADATA_BYTES bytes")
}
