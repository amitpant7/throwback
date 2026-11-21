package app.throwback.ui.about

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.throwback.R
import app.throwback.core.ui.NavRow
import app.throwback.core.ui.TbCard

/*
 * Support without Google Play tips (owner, 2026-11-21, DECISIONS.md #4e: Google Play can't pay the
 * developer in Nepal). One row, "Support me" (no subtext, owner), opens the website's support page,
 * which links to Ko-fi; the app itself has no payment link or payment words (Play's payments policy).
 * Nothing is locked: Make It Yours is free (core/Supporter.kt).
 * src/playTips has the same two functions with Google Play's tips.
 */

/** Nothing to restore without Google Play tips. */
@Suppress("UNUSED_PARAMETER")
internal fun refreshTips(context: Context) = Unit

@Suppress("UNUSED_PARAMETER") // same signature as the playTips version, which shows messages
@Composable
internal fun SupportTips(showMessage: (String) -> Unit, open: (String) -> Unit) {
    TbCard(Modifier.padding(top = 12.dp)) {
        NavRow(
            icon = R.drawable.ic_heart,
            title = stringResource(R.string.ui_support_me),
            onClick = { open(AboutLinks.SUPPORT_URL) },
        )
    }
}
