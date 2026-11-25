package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime

/**
 * 空对话:问候 + 一句价值主张 + 一张可左右滑的推广示例卡,底部贴着输入框两条慢慢反向滚动的示例。
 * 示例带手机上对应 App 的图标;点一条把完整提示词填进输入框,改好了再发。
 */
@Composable
internal fun EmptyState(
    conversation: String?,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
    onPick: (RecommendedTask) -> Unit,
    onSend: (RecommendedTask) -> Unit = onPick,
) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val hour = remember(conversation) { LocalTime.now().hour }
    // 每次打开一个空对话换一种排法;同一次里两遍加载用同一个,顺序不跳
    val random = remember(conversation) { kotlin.random.Random(System.nanoTime()) }
    val seed = remember(conversation) { random.nextLong() }
    val ideas by produceState<EmptyIdeas?>(null, seed) {
        value = withContext(Dispatchers.IO) { loadEmptyIdeas(context, kotlin.random.Random(seed)) }
    }
    Column(modifier.fillMaxSize()) {
        // 问候放在上方约三分之一处,下面紧跟推广卡;剩下的空间留给底部两条滚动示例
        Spacer(Modifier.weight(0.55f))
        Column(Modifier.fillMaxWidth().padding(horizontal = ComposerInset)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MikaLogo(26.dp, Modifier.border(0.5.dp, c.edge, RoundedCornerShape(8.dp)), corner = 8.dp)
                Text(remember(conversation) { todayLabel() }, style = MikaType.Caption, color = c.ink3)
            }
            Text(ChatCopy.greeting(hour), Modifier.padding(top = 14.dp), style = EmptyGreeting, color = c.ink)
            Text(ChatCopy.EMPTY_VALUE, Modifier.padding(top = 6.dp), style = EmptyLead, color = c.ink3)
        }
        ideas?.featured?.takeIf { it.isNotEmpty() }?.let { featured ->
            Spacer(Modifier.height(22.dp))
            FeaturedPager(featured, reduceMotion, onSend)
        }
        Spacer(Modifier.weight(1f))
        ideas?.rows?.forEachIndexed { i, row ->
            MarqueeRow(row, reverse = i % 2 == 1, animate = !reduceMotion, onPick = onPick, modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 推广示例:一次一张,左右滑或每隔几秒自动换下一张,下面几个小圆点。 */
@Composable
private fun FeaturedPager(items: List<Idea>, reduceMotion: Boolean, onPick: (RecommendedTask) -> Unit) {
    val c = MikaTheme.colors
    val pager = rememberPagerState { items.size }
    if (!reduceMotion && items.size > 1) {
        LaunchedEffect(pager.settledPage, pager.isScrollInProgress) {
            if (pager.isScrollInProgress) return@LaunchedEffect
            delay(5_000)
            pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    Column {
        HorizontalPager(
            pager,
            Modifier.fillMaxWidth().testTag("empty-featured"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = ComposerInset),
            pageSpacing = 8.dp,
        ) { page -> FeaturedCard(items[page], onPick) }
        if (items.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            ) {
                repeat(items.size) { i ->
                    val on = i == pager.currentPage
                    Box(Modifier.height(5.dp).width(if (on) 14.dp else 5.dp).clip(CircleShape).background(if (on) c.ink2 else c.hair))
                }
            }
        }
    }
}

@Composable
private fun FeaturedCard(idea: Idea, onPick: (RecommendedTask) -> Unit) {
    val c = MikaTheme.colors
    // 浅灰底、不描边,和底部的示例胶囊同一种材质;副标题是一句说明,点了直接把完整提示词发出去
    val blurb = idea.blurb ?: idea.task.prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    Row(
        Modifier.fillMaxWidth().clip(FeaturedShape).background(c.fill)
            .clickable(role = Role.Button) { onPick(idea.task) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMark(idea, 40.dp, tile = c.surface)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(idea.task.title, style = MikaType.BodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (blurb.isNotEmpty()) {
                Text(blurb, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // 点卡片直接发出去,所以右边是发送的样子(和输入框的发送键同形,小一号),不是「进下一页」的箭头
        Box(Modifier.padding(start = 8.dp).size(28.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(MikaIcons.Up, null, Modifier.size(15.dp), tint = c.ink)
        }
    }
}

/** App 图标;手机上没有对应 App 时是浅灰底上的线性图标。 */
@Composable
private fun AppMark(idea: Idea, size: androidx.compose.ui.unit.Dp, tile: Color = MikaTheme.colors.fill) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(size * 0.26f)
    val app = idea.app
    if (app != null) {
        Image(app, null, Modifier.size(size).clip(shape))
    } else {
        val site = idea.site
        Box(Modifier.size(size).clip(shape).background(tile), contentAlignment = Alignment.Center) {
            // 网站标志是单色的,跟着字色走,深色模式下自然变白
            if (site != null) Icon(painterResource(site), null, Modifier.size(size * 0.58f), tint = c.ink)
            else Icon(idea.fallback, null, Modifier.size(size * 0.5f), tint = c.ink2)
        }
    }
}

private val FeaturedShape = RoundedCornerShape(18.dp)

/** 问候上面那行日期:按界面语言的习惯写,如「11月25日 周二」「Tue, Nov 25」。 */
private fun todayLabel(): String {
    val locale = uiLocale()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdEEE")
    return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
}

private const val MARQUEE_DP_PER_SECOND = 22f

/**
 * 一条横着慢慢滚的示例。内容排两遍首尾相接,滚过一遍的宽度就回到开头,看起来没有尽头;
 * 两头淡出。关掉动画时改成可以手动左右滑的一排。
 */
@Composable
private fun MarqueeRow(items: List<Idea>, reverse: Boolean, animate: Boolean, onPick: (RecommendedTask) -> Unit, modifier: Modifier = Modifier) {
    val fade = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val edge = 28.dp.toPx() / size.width
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
    val pills: @Composable () -> Unit = { items.forEach { Pill(it, onPick) } }
    if (!animate) {
        Row(
            modifier.fillMaxWidth().then(fade).horizontalScroll(rememberScrollState()).padding(horizontal = ComposerInset),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { pills() }
        return
    }
    val speed = with(LocalDensity.current) { MARQUEE_DP_PER_SECOND.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var cycle by remember { mutableIntStateOf(0) }
    var shift by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(cycle) {
        if (cycle == 0) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) shift = (shift + speed * (now - last) / 1e9f) % cycle
            last = now
        }
    }
    Box(modifier.fillMaxWidth().clipToBounds().then(fade)) {
        Row(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset {
                // 往左走:0 → -cycle;往右走:-cycle → 0。从右往左排的语言里方向一起镜像
                val x = if (reverse) shift - cycle else -shift
                IntOffset((if (rtl) -x else x).toInt(), 0)
            },
        ) {
            repeat(3) { copy ->
                Row(
                    Modifier.padding(end = 8.dp).then(if (copy == 0) Modifier.onSizeChanged { cycle = it.width } else Modifier),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { pills() }
            }
        }
    }
}

/** 滚动条里的一颗:App 图标 + 一句话。 */
@Composable
private fun Pill(idea: Idea, onPick: (RecommendedTask) -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(c.fill)
            .clickable(role = Role.Button) { onPick(idea.task) }
            .padding(start = 7.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppMark(idea, 26.dp)
        Spacer(Modifier.width(8.dp))
        Text(idea.task.title, style = MikaType.Hint, color = c.ink, maxLines = 1)
    }
}

// ---------- 一问一答 ----------
