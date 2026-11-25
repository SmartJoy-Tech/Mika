package com.wangzan101.mika.chat.ui

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import kotlin.random.Random

/**
 * 空对话底部两条滚动条的案例库(`assets/chat/empty_ideas.json`)。按界面语言各一套:
 * 中文界面用国内 App 的那套(简体、繁体两种写法),其他语言用海外 App 的那套,文字是该语言的。
 * 每条绑定具体 App 的包名,手机上装了才显示,图标用那个 App 自己的。
 */
internal object IdeaLibrary {
    private const val ASSET_PATH = "chat/empty_ideas.json"

    /** 一条案例:场景(吃喝、购物……,只用来把同类错开)、能接它的包名(按先后取第一个装了的)、这条话。 */
    data class Entry(val scene: String, val packages: List<String>, val text: String)

    @Volatile private var cached: JSONObject? = null

    fun load(context: Context, locale: Locale): List<Entry> = runCatching {
        val root = cached ?: JSONObject(context.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) }).also { cached = it }
        val (set, key) = setFor(locale)
        val list = root.getJSONArray(set)
        (0 until list.length()).mapNotNull { i ->
            val e = list.getJSONObject(i)
            val texts = e.getJSONObject("text")
            val text = texts.optString(key).ifEmpty { texts.optString(if (set == "zh") "zh-Hans" else "en") }
            val pkgs = e.getJSONArray("packages").let { a -> (0 until a.length()).map(a::getString) }
            text.takeIf { it.isNotEmpty() }?.let { Entry(e.getString("scene"), pkgs, it) }
        }
    }.getOrDefault(emptyList())

    /** 界面语言 → (用哪套, 取哪种文字)。中文看文字体系或地区分简繁;印尼语老写法 in 当 id。 */
    internal fun setFor(locale: Locale): Pair<String, String> {
        val lang = locale.language.let { if (it == "in") "id" else it }
        if (lang == "zh") {
            val hant = locale.script == "Hant" || (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))
            return "zh" to (if (hant) "zh-Hant" else "zh-Hans")
        }
        return "intl" to lang
    }

    /**
     * 排成两条:每个场景内部打乱,场景之间轮流取一条(同类不挨着),
     * 再一条给第一行、一条给第二行。[random] 每次打开对话换一个,顺序常看常新。
     */
    internal fun <T> arrange(items: List<T>, scene: (T) -> String, random: Random): List<List<T>> {
        val groups = items.groupBy(scene).values.map { it.shuffled(random).toMutableList() }.shuffled(random)
        val woven = buildList {
            while (groups.any { it.isNotEmpty() }) groups.forEach { g -> if (g.isNotEmpty()) add(g.removeAt(0)) }
        }
        return listOf(woven.filterIndexed { i, _ -> i % 2 == 0 }, woven.filterIndexed { i, _ -> i % 2 == 1 }).filter { it.isNotEmpty() }
    }
}
