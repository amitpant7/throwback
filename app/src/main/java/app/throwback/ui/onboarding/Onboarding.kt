package app.throwback.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.sp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme
import app.throwback.ui.UiPrefs
import app.throwback.ui.common.LineIcon
import app.throwback.ui.common.PrimaryButton
import app.throwback.ui.common.QuietButton
import app.throwback.ui.common.SystemScreens
import app.throwback.ui.common.ThrowbackLogo
import app.throwback.ui.common.UiText
import app.throwback.ui.messages.InstagramApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * First launch, then (once, if the Instagram app or Lite is installed) "Instagram app found".
 * See UiApi.OnboardingFlow for the contract.
 */
@Composable
internal fun OnboardingFlowContent(onFinished: () -> Unit, onOpenMessagesSetup: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { UiPrefs.from(context) }
    val scope = rememberCoroutineScope()
    // Package of the installed Instagram app while the "found" screen is showing.
    var foundPackage by rememberSaveable { mutableStateOf<String?>(null) }
    // Lets "back" from the found screen and "Log in" again show it again in this session.
    var promptSeenHere by rememberSaveable { mutableStateOf(false) }

    val found = foundPackage
    if (found == null) {
        WelcomeScreen(onLogIn = {
            scope.launch {
                val pkg = InstagramApp.installedPackage(context)
                val showPrompt = pkg != null && (promptSeenHere || !prefs.instagramPromptShown.first())
                if (showPrompt) foundPackage = pkg else onFinished()
            }
        })
    } else {
        BackHandler { foundPackage = null }
        LaunchedEffect(Unit) {
            promptSeenHere = true
            prefs.write { setInstagramPromptShown() }
        }
        InstagramFoundScreen(
            onKeep = onOpenMessagesSetup,
            onRemove = {
                SystemScreens.openAppDetails(context, found)
                onFinished()
            },
            onLater = onFinished,
        )
    }
}

@Composable
private fun WelcomeScreen(onLogIn: () -> Unit) {
    val colors = ThrowbackTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .systemBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThrowbackLogo(32.dp)
                Text(stringResource(R.string.app_name), style = UiText.wordmark, color = colors.text)
            }
            Image(
                painter = painterResource(R.drawable.ui_illustration_photos),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp).height(210.dp),
            )
            Text(
                stringResource(R.string.ui_onb_title),
                style = UiText.hero,
                color = colors.text,
                modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
            )
            Text(
                stringResource(R.string.ui_onb_subtitle),
                style = UiText.lead,
                color = colors.muted,
                modifier = Modifier.padding(bottom = 20.dp),
            )
            FeatureGrid()
            Spacer(Modifier.height(24.dp))
        }
        PrimaryButton(stringResource(R.string.ui_onb_login), onClick = onLogIn)
        Text(
            stringResource(R.string.ui_onb_note),
            style = UiText.fine,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )
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
                pair.forEach { (label, isKept) -> FeatureItem(label, isKept, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FeatureItem(label: String, kept: Boolean, modifier: Modifier) {
    val colors = ThrowbackTheme.colors
    val description = if (kept) stringResource(R.string.ui_onb_kept_description, label) else label
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LineIcon(
            if (kept) R.drawable.ui_ic_check else R.drawable.ui_ic_blocked,
            tint = if (kept) colors.accentText else colors.muted,
        )
        Text(label, style = UiText.body14, color = if (kept) colors.text else colors.muted)
    }
}

@Composable
private fun InstagramFoundScreen(onKeep: () -> Unit, onRemove: () -> Unit, onLater: () -> Unit) {
    val colors = ThrowbackTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
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
                    icon = R.drawable.ui_ic_phone,
                    title = stringResource(R.string.ui_found_keep_title),
                    body = stringResource(R.string.ui_found_keep_body),
                    accentBadge = true,
                    onClick = onKeep,
                )
                ChoiceCard(
                    icon = R.drawable.ui_ic_trash,
                    title = stringResource(R.string.ui_found_remove_title),
                    body = stringResource(R.string.ui_found_remove_body),
                    accentBadge = false,
                    onClick = onRemove,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        QuietButton(stringResource(R.string.ui_found_later), onClick = onLater, color = colors.text, style = UiText.button.copy(fontWeight = FontWeight.Bold))
        Text(
            stringResource(R.string.ui_found_footer),
            style = UiText.sub,
            color = colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/** 64dp app tile with a "!" badge at its top-right corner. */
@Composable
private fun FoundIcon() {
    val colors = ThrowbackTheme.colors
    val description = stringResource(R.string.ui_found_icon_description)
    Box(Modifier.size(64.dp).semantics { contentDescription = description }) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(colors.surface2),
            contentAlignment = Alignment.Center,
        ) { LineIcon(R.drawable.ui_ic_camera_app, size = 30.dp) }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", color = colors.onAccent, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun ChoiceCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    accentBadge: Boolean,
    onClick: () -> Unit,
) {
    val colors = ThrowbackTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = colors.surface,
        contentColor = colors.text,
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
                    .background(if (accentBadge) colors.accent else colors.surface2),
                contentAlignment = Alignment.Center,
            ) {
                LineIcon(icon, tint = if (accentBadge) colors.onAccent else colors.text)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = UiText.rowStrong.copy(fontWeight = FontWeight.Bold), color = colors.text)
                Text(body, style = UiText.sub, color = colors.muted, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}
