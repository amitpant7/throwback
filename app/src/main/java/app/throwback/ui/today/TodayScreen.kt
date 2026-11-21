package app.throwback.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.SectionLabel
import app.throwback.core.ui.SettingsScaffold
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.TbProgressBar
import app.throwback.core.ui.UiText
import app.throwback.core.ui.ValueRow
import app.throwback.core.ui.elapsedText
import app.throwback.core.ui.minSecText
import app.throwback.core.ui.remainingText

/** What "Your day" shows. Page counts are null until the feed has been open once. */
data class TodayStats(
    /** Seconds per area, in display order: feed, stories, messages, explore, other. */
    val areas: Map<String, Long>? = null,
    val limit: Limit = Limit.None,
    val exploreLeftSec: Int? = null,
    val exploreAllowanceSec: Int = 0,
    val discoverShown: Int? = null,
    val discoverMax: Int = 0,
    val adsRemoved: Int? = null,
    val suggestionsRemoved: Int? = null,
    /** Instagram alerts the relay removed today; null when the Instagram app isn't connected. */
    val alertsRemoved: Int? = null,
    /** The connected Instagram app today; null when it isn't connected. */
    val instagramApp: InstagramAppDay? = null,
) {
    /** Story time used of its budget (or the lock's minutes left), the 5-minute visit, time in the app. */
    data class InstagramAppDay(
        val storyUsedMillis: Long,
        val storyBudgetMillis: Long,
        val storyLockMinutes: Int,
        val visitUsed: Boolean,
        val appMillis: Long,
    )

    sealed interface Limit {
        data object None : Limit
        data class Left(val millis: Long) : Limit
        data object Reached : Limit
    }

    val totalSec: Long get() = areas?.values?.sum() ?: 0L
}

internal val TODAY_AREAS = listOf("feed", "stories", "messages", "explore", "other")

@Composable
internal fun TodayScreen(stats: TodayStats, onBack: () -> Unit) {
    val c = ThrowbackTheme.colors
    SettingsScaffold(stringResource(R.string.ui_today_title), onBack = onBack) {
        TimeCard(stats)

        SectionLabel(stringResource(R.string.ui_today_section_allowances))
        TbCard {
            val exploreLeft = stats.exploreLeftSec
            AllowanceRow(
                title = stringResource(R.string.ui_today_explore),
                value = when {
                    exploreLeft == null -> null
                    exploreLeft <= 0 -> stringResource(R.string.ui_today_explore_done)
                    else -> stringResource(
                        R.string.ui_today_explore_value,
                        minSecText(exploreLeft * 1000L),
                        minSecText(stats.exploreAllowanceSec * 1000L),
                    )
                },
                fraction = if (exploreLeft != null && stats.exploreAllowanceSec > 0) {
                    exploreLeft.toFloat() / stats.exploreAllowanceSec
                } else {
                    null
                },
            )
            CardDivider()
            val shown = stats.discoverShown
            AllowanceRow(
                title = stringResource(R.string.ui_today_discover),
                value = if (shown == null) null else stringResource(R.string.ui_today_discover_value, (stats.discoverMax - shown).coerceAtLeast(0), stats.discoverMax),
                fraction = if (shown != null && stats.discoverMax > 0) 1f - shown.toFloat() / stats.discoverMax else null,
            )
        }

        stats.instagramApp?.let { ig ->
            SectionLabel(stringResource(R.string.ui_today_section_app))
            TbCard {
                AllowanceRow(
                    title = stringResource(R.string.ui_today_story_time),
                    value = if (ig.storyLockMinutes > 0) {
                        stringResource(R.string.ui_today_story_locked, ig.storyLockMinutes)
                    } else {
                        stringResource(R.string.ui_today_story_used, minSecText(ig.storyUsedMillis), minSecText(ig.storyBudgetMillis))
                    },
                )
                CardDivider()
                AllowanceRow(
                    title = stringResource(R.string.ui_today_visit),
                    value = stringResource(if (ig.visitUsed) R.string.ui_today_visit_used else R.string.ui_today_visit_free),
                )
                CardDivider()
                AllowanceRow(
                    title = stringResource(R.string.ui_today_app_time),
                    value = elapsedText(ig.appMillis),
                )
            }
        }

        SectionLabel(stringResource(R.string.ui_today_section_removed))
        TbCard {
            CountRow(stringResource(R.string.ui_today_ads), stats.adsRemoved)
            CardDivider()
            CountRow(stringResource(R.string.ui_today_suggested), stats.suggestionsRemoved)
            if (stats.alertsRemoved != null) {
                CardDivider()
                CountRow(stringResource(R.string.ui_today_alerts), stats.alertsRemoved)
            }
        }
        Text(
            stringResource(if (stats.areas == null) R.string.ui_today_no_page else R.string.ui_today_note),
            style = UiText.sub,
            color = c.muted,
            modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun TimeCard(stats: TodayStats) {
    val c = ThrowbackTheme.colors
    val areaColors = areaColors()
    TbCard(contentPadding = PaddingValues(18.dp)) {
        Text(
            elapsedText(stats.totalSec * 1000),
            style = UiText.hero.copy(fontSize = 40.sp, lineHeight = 44.sp),
            color = c.text,
        )
        Text(stringResource(R.string.ui_today_on_instagram), style = UiText.body14, color = c.muted)
        val limitText = when (val l = stats.limit) {
            TodayStats.Limit.None -> stringResource(R.string.ui_today_no_limit)
            is TodayStats.Limit.Left -> stringResource(R.string.ui_today_limit_left, remainingText(l.millis))
            TodayStats.Limit.Reached -> stringResource(R.string.ui_today_limit_reached)
        }
        Text(
            limitText,
            style = UiText.body14Strong,
            color = if (stats.limit is TodayStats.Limit.None) c.muted else c.accentText,
            modifier = Modifier.padding(top = 6.dp),
        )
        val areas = stats.areas
        if (areas != null && stats.totalSec > 0) {
            Spacer(Modifier.height(16.dp))
            // One bar split by area, then a legend with minutes.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(c.surface2)
                    .clearAndSetSemantics {},
            ) {
                TODAY_AREAS.forEachIndexed { i, a ->
                    val sec = areas[a] ?: 0L
                    if (sec > 0) {
                        Box(Modifier.weight(sec.toFloat()).fillMaxHeight().background(areaColors[i]))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TODAY_AREAS.forEachIndexed { i, a ->
                    val sec = areas[a] ?: 0L
                    if (sec >= 1) {
                        val name = areaName(a)
                        val value = elapsedText(sec * 1000)
                        val description = stringResource(R.string.label_value, name, value)
                        Row(
                            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(areaColors[i]))
                            Text(name, style = UiText.body14, color = c.text, modifier = Modifier.padding(start = 10.dp).weight(1f))
                            Text(value, style = UiText.body14, color = c.muted)
                        }
                    }
                }
            }
        }
    }
}

/** A title and what's left; [value] is null until the page has counted it. The bar shows [fraction] left. */
@Composable
private fun AllowanceRow(title: String, value: String?, fraction: Float? = null) {
    ValueRow(title, value) {
        if (fraction != null) TbProgressBar({ fraction }, Modifier.padding(top = 10.dp))
    }
}

/** What was removed today; [count] is null until the page has counted it. */
@Composable
private fun CountRow(title: String, count: Int?) {
    ValueRow(title, count?.toString(), strong = true)
}

@Composable
private fun areaColors(): List<Color> {
    val c = ThrowbackTheme.colors
    return listOf(c.accent, c.accent.copy(alpha = 0.6f), c.accentText.copy(alpha = 0.35f), c.muted, c.line)
}

@Composable
private fun areaName(a: String): String = stringResource(
    when (a) {
        "feed" -> R.string.ui_today_area_feed
        "stories" -> R.string.ui_today_area_stories
        "messages" -> R.string.ui_today_area_messages
        "explore" -> R.string.ui_today_area_explore
        else -> R.string.ui_today_area_other
    },
)
