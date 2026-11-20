package app.throwback.core

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme

/**
 * The Throwback mark (placeholder logo until the user designs one). Owned by integration.
 * On the home feed it sits where Instagram's wordmark was and opens Settings.
 */
@Composable
fun ThrowbackMarkButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.open_settings)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = ThrowbackTheme.colors.accent,
        modifier = modifier.size(36.dp).semantics { contentDescription = label; role = Role.Button },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.ic_throwback_mark),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ThrowbackTheme.colors.onAccent),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
