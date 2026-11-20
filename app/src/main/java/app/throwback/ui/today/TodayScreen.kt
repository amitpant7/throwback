package app.throwback.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.core.ui.CardDivider
import app.throwback.core.ui.SectionLabel
import app.throwback.core.ui.TbCard
import app.throwback.core.ui.TopBar
import app.throwback.core.ui.UiText

/** What "Your day" shows. Page counts are null until the feed has been open once. */
data class TodayStats(
    /** Seconds per area, in display order: feed, stories, messages, explore, other. */
    val areas: Map<String, Long>? = null,
    val limit: Limit = Limit.None,
    val exploreLeftSec: Int? = null,
    val exploreAllowanceSec: Int = 0,
    val reelCooldownMs: Long? = null,
    val discoverShown: Int? = null,
    val discoverMax: Int = 0,
    val adsRemoved: Int? = null,
    val suggestionsRemoved: Int? = null,
    /** Instagram alerts the relay removed today; null when Messages & calls isn't set up. */
    val alertsKept: Int? = null,
    /** The connected Instagram app today; null when it isn't connected. */
    val instagramApp: InstagramAppDay? = null,
) {
    data class InstagramAppDay(val storiesUsed: Int, val storiesMax: Int, val storyWaitMinutes: Int?, val visitUsed: Boolean, val appMinutes: Int)

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
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.ui_today_title), onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            TimeCard(stats)

            SectionLabel(stringResource(R.string.ui_today_section_allowances))
            TbCard {
                val exploreLeft = stats.exploreLeftSec
                AllowanceRow(
                    title = stringResource(R.string.ui_today_explore),
                    value = when {
                        exploreLeft == null -> "–"
                        exploreLeft <= 0 -> stringResource(R.string.ui_today_explore_done)
                        else -> stringResource(R.string.ui_today_explore_value, clock(exploreLeft), clock(stats.exploreAllowanceSec))
                    },
                    fraction = if (exploreLeft != null && stats.exploreAllowanceSec > 0) {
                        exploreLeft.toFloat() / stats.exploreAllowanceSec
                    } else {
                        null
                    },
                )
                CardDivider()
                val cool = stats.reelCooldownMs
                AllowanceRow(
                    title = stringResource(R.string.ui_today_reels),
                    value = when {
                        cool == null -> "–"
                        cool > 0 -> stringResource(R.string.ui_today_reels_cooling, duration(cool / 1000))
                        else -> stringResource(R.string.ui_today_reels_free)
                    },
                )
                CardDivider()
                val shown = stats.discoverShown
                AllowanceRow(
                    title = stringResource(R.string.ui_today_discover),
                    value = if (shown == null) "–" else stringResource(R.string.ui_today_discover_value, (stats.discoverMax - shown).coerceAtLeast(0), stats.discoverMax),
                    fraction = if (shown != null && stats.discoverMax > 0) 1f - shown.toFloat() / stats.discoverMax else null,
                )
            }

            stats.instagramApp?.let { ig ->
                SectionLabel(stringResource(R.string.ui_today_section_app))
                TbCard {
                    AllowanceRow(
                        title = stringResource(R.string.ui_today_story_passes),
                        value = when {
                            ig.storyWaitMinutes == null -> stringResource(R.string.ui_today_story_tomorrow, ig.storiesUsed, ig.storiesMax)
                            ig.storyWaitMinutes > 0 -> stringResource(R.string.ui_today_story_wait, ig.storiesUsed, ig.storiesMax, ig.storyWaitMinutes)
                            else -> stringResource(R.string.ui_today_story_ready, ig.storiesUsed, ig.storiesMax)
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
                        value = duration(ig.appMinutes * 60L),
                    )
                }
            }

            SectionLabel(stringResource(R.string.ui_today_section_removed))
            TbCard {
                CountRow(stringResource(R.string.ui_today_ads), stats.adsRemoved)
                CardDivider()
                CountRow(stringResource(R.string.ui_today_suggested), stats.suggestionsRemoved)
                if (stats.alertsKept != null) {
                    CardDivider()
                    CountRow(stringResource(R.string.ui_today_alerts), stats.alertsKept)
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
}

@Composable
private fun TimeCard(stats: TodayStats) {
    val c = ThrowbackTheme.colors
    val areaColors = areaColors()
    TbCard {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            Text(
                duration(stats.totalSec),
                style = UiText.hero.copy(fontSize = 40.sp, lineHeight = 44.sp),
                color = c.text,
            )
            Text(stringResource(R.string.ui_today_on_instagram), style = UiText.body14, color = c.muted)
            val limitText = when (val l = stats.limit) {
                TodayStats.Limit.None -> stringResource(R.string.ui_today_no_limit)
                is TodayStats.Limit.Left -> stringResource(R.string.ui_today_limit_left, duration(l.millis / 1000))
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
                            val value = duration(sec)
                            Row(
                                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$name, $value" },
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
}

@Composable
private fun AllowanceRow(title: String, value: String, fraction: Float? = null) {
    val c = ThrowbackTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = UiText.row, color = c.text, modifier = Modifier.weight(1f))
            Text(value, style = UiText.body14, color = c.muted)
        }
        if (fraction != null) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(c.surface2)
                    .clearAndSetSemantics {},
            ) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
            }
        }
    }
}

@Composable
private fun CountRow(title: String, count: Int?) {
    val c = ThrowbackTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = UiText.row, color = c.text, modifier = Modifier.weight(1f))
        Text(count?.toString() ?: "–", style = UiText.row.copy(fontWeight = FontWeight.SemiBold), color = c.text)
    }
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

/** "45 min", "1 h 5 min"; under a minute shows "0 min". */
@Composable
internal fun duration(seconds: Long): String {
    val min = (seconds / 60).toInt()
    if (seconds in 1..59) return stringResource(R.string.ui_today_under_minute)
    return if (min >= 60) stringResource(R.string.ui_today_hours, min / 60, min % 60) else stringResource(R.string.ui_today_minutes, min)
}

/** "3:05". */
internal fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
