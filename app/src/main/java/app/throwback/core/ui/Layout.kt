package app.throwback.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.throwback.core.theme.ThrowbackTheme

/**
 * A settings screen: the top bar, then a scrolling column with [contentPadding]. It keeps clear of the
 * navigation bar and above the keyboard (the app draws edge to edge, so each screen makes room for
 * them). With [fillViewport] the column is at least as tall as the screen (see [ScreenColumn]).
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    fillViewport: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        TbTopBar(title, onBack)
        ScreenColumn(contentPadding, Modifier.weight(1f), Alignment.Start, fillViewport, content)
    }
}

/**
 * A screen's scrolling column, kept clear of the navigation bar (its padding is inside the scroll, so
 * the content scrolls behind the transparent bar). With [fillViewport] it is at least as tall as the
 * screen, so children can push buttons to the bottom with `Spacer(Modifier.weight(1f))`, as in the
 * mockups.
 */
@Composable
internal fun ScreenColumn(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    fillViewport: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val minHeight = if (fillViewport) maxHeight else 0.dp
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(contentPadding)
                .navigationBarsPadding(),
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}

/** Rounded surface card holding rows or free content: full width, 18dp corners unless [radius] says otherwise. */
@Composable
internal fun TbCard(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(ThrowbackTheme.colors.surface)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/** Hairline between rows inside a card. */
@Composable
internal fun CardDivider() {
    HorizontalDivider(thickness = 1.dp, color = ThrowbackTheme.colors.line)
}

/** Small muted heading above a card or a group of controls ("Focus", "How long per day?"). */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, topPadding: Dp = 22.dp) {
    Text(
        text = text,
        style = UiText.section,
        color = ThrowbackTheme.colors.muted,
        modifier = modifier
            .padding(start = 6.dp, end = 6.dp, top = topPadding, bottom = 8.dp)
            .semantics { heading() },
    )
}

/**
 * Keeps touches from reaching whatever is under a full-screen layer (the Instagram page). It doesn't
 * consume them, so the layer's own buttons work as usual (a finger always moves a little), and it adds
 * no semantics: TalkBack still reads the layer item by item, where a clickable layer was one item.
 */
internal fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}
