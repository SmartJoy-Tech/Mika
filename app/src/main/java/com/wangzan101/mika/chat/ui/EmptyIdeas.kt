package com.wangzan101.mika.chat.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.graphics.drawable.toBitmap
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import kotlin.random.Random
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.ui.theme.MikaIcons

/**
 * 一条空对话里的示例:点了把 [task] 的提示词填进输入框;[app] 是手机上对应 App 的图标,
 * 没有时看 [site](网站自带的标志),再没有用 [fallback]。
 */
internal data class Idea(val task: RecommendedTask, val app: ImageBitmap?, val fallback: ImageVector, val site: Int? = null, val blurb: String? = null)

/** 内置的灵感示例:文字 + 用哪类系统动作找到接它的 App。找不到 App 的不显示。 */
private class Seed(val id: String, val text: Int, val intent: () -> Intent, val fallback: ImageVector)

private val seeds = listOf(
    Seed("maps", R.string.chat_idea_maps, { Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=coffee")) }, MikaIcons.Globe),
    Seed("camera", R.string.chat_idea_camera, { Intent(MediaStore.ACTION_IMAGE_CAPTURE) }, MikaIcons.Camera),
    Seed("sms", R.string.chat_idea_sms, { Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")) }, MikaIcons.Message),
    Seed("news", R.string.chat_idea_news, { Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")) }, MikaIcons.Search),
    Seed("dark", R.string.chat_idea_dark, { Intent(Settings.ACTION_SETTINGS) }, MikaIcons.Moon),
    Seed("music", R.string.chat_idea_music, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC) }, MikaIcons.Spark),
    Seed("email", R.string.chat_idea_email, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL) }, MikaIcons.Message),
    Seed("screenshot", R.string.chat_idea_screenshot, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY) }, MikaIcons.Photo),
    Seed("meeting", R.string.chat_idea_meeting, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR) }, MikaIcons.Calendar),
)

/** 案例库里手机装了的少于这么多条,就拿系统动作的示例补上,免得滚动条太空。 */
private const val MIN_LIBRARY = 8

/** 空对话的内容:上面轮播的几条推广示例,下面两条滚动的示例。 */
internal data class EmptyIdeas(val featured: List<Idea>, val rows: List<List<Idea>>)

/**
 * 上面的推广卡是 [ChatCopy.promos] 写死的几条,按当前界面语言;下面两条滚动条来自 [IdeaLibrary] 案例库,
 * 只列手机上装了的 App。装得太少时用几条系统动作(地图、相机、日历……)补上。读 PackageManager,放在后台线程调用。
 */
internal fun loadEmptyIdeas(context: Context, random: Random): EmptyIdeas {
    val pm = context.packageManager
    fun iconOf(pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() ?: return null
    }
    fun handler(intent: Intent): String? = runCatching {
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.firstOrNull { it != "android" && it != context.packageName }
    }.getOrNull()

    val featured = ChatCopy.promos.mapIndexed { i, p ->
        val (pkg, site) = when (p.kind) {
            ChatCopy.ExampleKind.Web -> null to R.drawable.ic_site_github
            // 中文界面是小红书,其他语言是 X;没装 App 时 X 用自己的标志
            ChatCopy.ExampleKind.Follow -> if (uiLocale().language == "zh") "com.xingin.xhs" to null else "com.twitter.android" to R.drawable.ic_site_x
            ChatCopy.ExampleKind.GroupChat -> "com.tencent.mm" to null
            ChatCopy.ExampleKind.Share -> context.packageName to null
            else -> null to null
        }
        Idea(RecommendedTask("promo-$i", p.title, p.prompt), pkg?.let(::iconOf), fallbackFor(p.kind), site, p.blurb)
    }
    val library = IdeaLibrary.load(context, uiLocale()).mapIndexedNotNull { i, e ->
        val icon = e.packages.firstNotNullOfOrNull(::iconOf) ?: return@mapIndexedNotNull null
        e.scene to Idea(RecommendedTask("idea-lib-$i", e.text, e.text), icon, MikaIcons.Spark)
    }
    val filler = if (library.size >= MIN_LIBRARY) emptyList() else seeds.mapNotNull { seed ->
        val pkg = handler(seed.intent()) ?: return@mapNotNull null
        val text = tr(seed.text)
        "system-${seed.id}" to Idea(RecommendedTask("idea-${seed.id}", text, text), iconOf(pkg), seed.fallback)
    }
    val rows = IdeaLibrary.arrange(library + filler, { it.first }, random).map { row -> row.map { it.second } }
    return EmptyIdeas(featured, rows)
}

/** 已经画好的 App 图标,按包名;App 换了图标要等进程重启才更新,够用。 */
private val icons = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

private fun fallbackFor(kind: ChatCopy.ExampleKind): ImageVector = when (kind) {
    ChatCopy.ExampleKind.Web -> MikaIcons.Globe
    ChatCopy.ExampleKind.Follow -> MikaIcons.Person
    ChatCopy.ExampleKind.GroupChat -> MikaIcons.Message
    ChatCopy.ExampleKind.Share -> MikaIcons.Share
    ChatCopy.ExampleKind.Alarm -> MikaIcons.Alarm
    ChatCopy.ExampleKind.Schedule -> MikaIcons.Calendar
}
