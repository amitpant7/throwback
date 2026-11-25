package app.throwback.ui.onboarding

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.prefs.AppPrefs
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.TbButton
import app.throwback.core.ui.TbButtonStyle
import app.throwback.core.ui.TbIcon
import app.throwback.core.ui.ThrowbackLogo
import app.throwback.core.ui.UiText
import app.throwback.core.ui.blockTouches
import app.throwback.ui.common.SystemScreens
import app.throwback.ui.connect.InstagramApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * First launch: four welcome pages (owner, 2026-11-25, DECISIONS #34ll). What Instagram looks like in
 * Throwback, its own focus tools (with "Set a daily limit now", [onSetLimit]), where its settings are
 * (hold Home), and privacy. "Log in to
 * Instagram" on the last page calls [onFinished]; the login itself happens on Instagram's page. The
 * "Instagram app found" choice is shown after login (InstagramAppPrompt).
 */
@Composable
internal fun OnboardingFlow(onFinished: () -> Unit, onSetLimit: () -> Unit, backEnabled: Boolean = true) {
    WelcomePager(onLogIn = onFinished, onSetLimit = onSetLimit, backEnabled = backEnabled)
}

/**
 * True when the "Instagram app found" choice should be offered: Instagram (or Lite) is installed
 * and the user hasn't seen the choice yet.
 */
internal suspend fun shouldOfferInstagramPrompt(context: Context): Boolean =
    InstagramApp.installedPackage(context) != null && !AppPrefs.from(context).instagramPromptShown.first()

/** The "You still have the Instagram app" choice, shown once after the first login. */
@Composable
internal fun InstagramAppPrompt(onOpenMessagesSetup: () -> Unit, onDone: () -> Unit, backEnabled: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.from(context) }
    val found = remember { InstagramApp.installedPackage(context) }
    LaunchedEffect(Unit) { prefs.write { setInstagramPromptShown() } }
    BackHandler(enabled = backEnabled, onBack = onDone)
    InstagramFoundScreen(
        onKeep = onOpenMessagesSetup,
        onRemove = {
            if (found != null) SystemScreens.openAppDetails(context, found)
            onDone()
        },
        onLater = onDone,
    )
}

private const val WELCOME_PAGES = 4

@Composable
private fun WelcomePager(onLogIn: () -> Unit, onSetLimit: () -> Unit, backEnabled: Boolean) {
    val colors = ThrowbackTheme.colors
    val pager = rememberPagerState(pageCount = { WELCOME_PAGES })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == WELCOME_PAGES - 1
    // Back steps to the previous page; on the first, it leaves as usual.
    BackHandler(enabled = backEnabled && pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .systemBarsPadding()
            .padding(bottom = 28.dp),
    ) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) { page ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThrowbackLogo(32.dp)
                    Text(stringResource(R.string.app_name), style = UiText.wordmark, color = colors.text)
                }
                when (page) {
                    0 -> WelcomeInstagramPage()
                    1 -> WelcomeFocusPage(onSetLimit)
                    2 -> WelcomeSettingsPage()
                    else -> WelcomePrivatePage()
                }
            }
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            PageDots(pager.currentPage, WELCOME_PAGES, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp))
            if (last) {
                TbButton(stringResource(R.string.ui_onb_login), onClick = onLogIn)
            } else {
                TbButton(
                    stringResource(R.string.ui_onb_next),
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                )
            }
            Text(
                stringResource(R.string.ui_onb_private_note),
                style = UiText.fine,
                color = colors.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
        }
    }
}

/** Page 1: Instagram, the way it used to be (what's removed and what stays). */
@Composable
private fun WelcomeInstagramPage() {
    Image(
        painter = painterResource(R.drawable.ui_illustration_photos),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp).height(210.dp),
    )
    WelcomeTitle(stringResource(R.string.ui_onb_title), stringResource(R.string.ui_onb_subtitle))
    FeatureGrid()
}

/** Page 2: Throwback's own focus tools; the daily limit can be set up right here. */
@Composable
private fun WelcomeFocusPage(onSetLimit: () -> Unit) {
    Spacer(Modifier.height(36.dp))
    WelcomeTitle(stringResource(R.string.ui_onb_focus_title), stringResource(R.string.ui_onb_focus_subtitle))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FocusCard(R.drawable.ic_clock, R.string.ui_onb_focus_limit_title, R.string.ui_onb_focus_limit_body)
        FocusCard(R.drawable.ic_quote, R.string.ui_onb_focus_pause_title, R.string.ui_onb_focus_pause_body)
        FocusCard(R.drawable.ic_usage, R.string.ui_onb_focus_day_title, R.string.ui_onb_focus_day_body)
    }
    Spacer(Modifier.height(16.dp))
    TbButton(
        stringResource(R.string.ui_onb_focus_set_limit),
        onClick = onSetLimit,
        style = TbButtonStyle.Secondary,
        icon = R.drawable.ic_lock,
    )
}

/** Page 3: Throwback's settings are one hold of the Home tab away (drawn, not a screenshot of Instagram). */
@Composable
private fun WelcomeSettingsPage() {
    val colors = ThrowbackTheme.colors
    Spacer(Modifier.height(36.dp))
    WelcomeTitle(stringResource(R.string.ui_onb_settings_title), stringResource(R.string.ui_onb_settings_subtitle))
    HoldHomeIllustration(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp))
    Text(stringResource(R.string.ui_onb_settings_body), style = UiText.body14, color = colors.muted)
}

/**
 * A bottom bar like the one under the feed (Home, Search, Messages, Profile; no Reels), with Home
 * pressed: a pulsing ring and a "Hold" bubble above it. The icons are drawn generic outlines.
 */
@Composable
private fun HoldHomeIllustration(modifier: Modifier = Modifier) {
    val colors = ThrowbackTheme.colors
    val description = stringResource(R.string.ui_onb_settings_picture)
    val pulse = rememberInfiniteTransition(label = "hold").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "ring",
    )
    Column(modifier.clearAndSetSemantics { contentDescription = description }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val slot = maxWidth / 4
            // "Hold" bubble over the Home tab.
            Column(Modifier.width(slot).padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(colors.accent).padding(horizontal = 12.dp, vertical = 5.dp),
                ) { Text(stringResource(R.string.ui_onb_settings_hold), style = UiText.subStrong, color = colors.onAccent) }
                Canvas(Modifier.size(width = 14.dp, height = 7.dp)) {
                    drawPath(
                        Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width, 0f)
                            lineTo(size.width / 2f, size.height)
                            close()
                        },
                        colors.accent,
                    )
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = colors.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val p = pulse.value
                    Canvas(Modifier.size(56.dp)) {
                        drawCircle(colors.accent.copy(alpha = 0.18f), radius = size.minDimension / 2f * 0.62f)
                        drawCircle(
                            colors.accent.copy(alpha = (1f - p) * 0.55f),
                            radius = size.minDimension / 2f * (0.62f + 0.38f * p),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                    TabIcon(TabKind.Home, colors.text)
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { TabIcon(TabKind.Search, colors.muted) }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    TbIcon(R.drawable.ic_chat, tint = colors.muted, size = 24.dp)
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { TabIcon(TabKind.Profile, colors.muted) }
            }
        }
    }
}

private enum class TabKind { Home, Search, Profile }

/** Plain outline icons for the drawn tab bar (a house, a magnifier, a head and shoulders). */
@Composable
private fun TabIcon(kind: TabKind, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            TabKind.Home -> drawPath(
                Path().apply {
                    moveTo(w * 0.12f, w * 0.45f)
                    lineTo(w * 0.5f, w * 0.12f)
                    lineTo(w * 0.88f, w * 0.45f)
                    lineTo(w * 0.88f, w * 0.88f)
                    lineTo(w * 0.12f, w * 0.88f)
                    close()
                },
                color,
                style = stroke,
            )
            TabKind.Search -> {
                drawCircle(color, radius = w * 0.3f, center = Offset(w * 0.44f, w * 0.44f), style = stroke)
                drawLine(color, Offset(w * 0.66f, w * 0.66f), Offset(w * 0.88f, w * 0.88f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            TabKind.Profile -> {
                drawCircle(color, radius = w * 0.2f, center = Offset(w * 0.5f, w * 0.36f), style = stroke)
                drawArc(color, 200f, 140f, false, topLeft = Offset(w * 0.14f, w * 0.62f), size = Size(w * 0.72f, w * 0.6f), style = stroke)
            }
        }
    }
}

/** Page 4: privacy, next to the login button. */
@Composable
private fun WelcomePrivatePage() {
    Spacer(Modifier.height(36.dp))
    WelcomeTitle(stringResource(R.string.ui_onb_private_title), stringResource(R.string.ui_onb_private_subtitle))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PrivateRow(R.drawable.ic_info, R.string.ui_onb_private_password)
        PrivateRow(R.drawable.ic_shield, R.string.ui_onb_private_nothing)
        PrivateRow(R.drawable.ic_code, R.string.ui_onb_private_code)
    }
}

@Composable
private fun WelcomeTitle(title: String, subtitle: String) {
    val colors = ThrowbackTheme.colors
    Text(title, style = UiText.hero, color = colors.text, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
    Text(subtitle, style = UiText.lead, color = colors.muted, modifier = Modifier.padding(bottom = 20.dp))
}

@Composable
private fun FocusCard(@DrawableRes icon: Int, title: Int, body: Int) {
    val colors = ThrowbackTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface2),
            contentAlignment = Alignment.Center,
        ) { TbIcon(icon, tint = colors.accentText) }
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = UiText.rowStrong, color = colors.text)
            Text(stringResource(body), style = UiText.sub, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PrivateRow(@DrawableRes icon: Int, text: Int) {
    val colors = ThrowbackTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TbIcon(icon, tint = colors.accentText)
        Text(stringResource(text), style = UiText.body14, color = colors.text, modifier = Modifier.weight(1f))
    }
}

/** Which welcome page is showing; one element for TalkBack ("Page 2 of 3"). */
@Composable
private fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val colors = ThrowbackTheme.colors
    val description = stringResource(R.string.ui_onb_page, current + 1, count)
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(width = if (i == current) 20.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (i == current) colors.accent else colors.line),
            )
        }
    }
}

@Composable
private fun FeatureGrid() {
    val removed = listOf(
        R.string.ui_onb_no_reels,
        R.string.ui_onb_no_explore,
        R.string.ui_onb_no_suggested,
        R.string.ui_onb_no_ads,
    ).map { stringResource(it) to false }
    val kept = listOf(R.string.ui_onb_stories, R.string.ui_onb_messages).map { stringResource(it) to true }
    val items = removed + kept
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { (label, isKept) ->
                    if (isKept) {
                        FeatureItem(
                            label, R.drawable.ic_check, highlighted = true, Modifier.weight(1f),
                            description = stringResource(R.string.ui_onb_kept_description, label),
                        )
                    } else {
                        FeatureItem(label, R.drawable.ic_blocked, highlighted = false, Modifier.weight(1f))
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One item: [highlighted] in the accent colour (kept, or about Throwback), else muted (removed). */
@Composable
private fun FeatureItem(
    label: String,
    @DrawableRes icon: Int,
    highlighted: Boolean,
    modifier: Modifier,
    description: String = label,
) {
    val colors = ThrowbackTheme.colors
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TbIcon(icon, tint = if (highlighted) colors.accentText else colors.muted)
        Text(label, style = UiText.body14, color = if (highlighted) colors.text else colors.muted)
    }
}

@Composable
private fun InstagramFoundScreen(onKeep: () -> Unit, onRemove: () -> Unit, onLater: () -> Unit) {
    val colors = ThrowbackTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .blockTouches() // over the page: nothing reaches it
            .systemBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 56.dp)) {
            FoundIcon()
            Text(
                stringResource(R.string.ui_found_title),
                style = UiText.bigTitle,
                color = colors.text,
                modifier = Modifier.padding(top = 24.dp, bottom = 10.dp).semantics { heading() },
            )
            Text(
                stringResource(R.string.ui_found_body),
                style = UiText.lead,
                color = colors.muted,
                modifier = Modifier.padding(bottom = 24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceCard(
                    icon = R.drawable.ic_phone,
                    title = stringResource(R.string.ui_found_keep_title),
                    body = stringResource(R.string.ui_found_keep_body),
                    recommended = true,
                    onClick = onKeep,
                )
                ChoiceCard(
                    icon = R.drawable.ic_trash,
                    title = stringResource(R.string.ui_found_remove_title),
                    body = stringResource(R.string.ui_found_remove_body),
                    recommended = false,
                    onClick = onRemove,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        TbButton(
            stringResource(R.string.ui_found_later),
            onClick = onLater,
            style = TbButtonStyle.Plain,
            textStyle = UiText.button.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            stringResource(R.string.ui_found_footer),
            style = UiText.sub,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/** 64dp app tile with a "!" badge at its top-right corner; one image for TalkBack (not "…, !"). */
@Composable
private fun FoundIcon() {
    val colors = ThrowbackTheme.colors
    val description = stringResource(R.string.ui_found_icon_description)
    Box(Modifier.size(64.dp).clearAndSetSemantics { contentDescription = description }) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(colors.surface2),
            contentAlignment = Alignment.Center,
        ) { TbIcon(R.drawable.ic_camera_app, size = 30.dp) }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", style = UiText.row.copy(fontWeight = FontWeight.ExtraBold), color = colors.onAccent)
        }
    }
}

@Composable
private fun ChoiceCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    val colors = ThrowbackTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = colors.surface,
        contentColor = colors.text,
        border = if (recommended) BorderStroke(1.5.dp, colors.accent) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (recommended) colors.accent else colors.surface2),
                contentAlignment = Alignment.Center,
            ) {
                TbIcon(icon, tint = if (recommended) colors.onAccent else colors.text)
            }
            Column(Modifier.weight(1f)) {
                if (recommended) {
                    Text(stringResource(R.string.ui_found_recommended), style = UiText.fine.copy(fontWeight = FontWeight.Bold), color = colors.accentText)
                }
                Text(title, style = UiText.rowStrong.copy(fontWeight = FontWeight.Bold), color = colors.text)
                Text(body, style = UiText.sub, color = colors.muted, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}
