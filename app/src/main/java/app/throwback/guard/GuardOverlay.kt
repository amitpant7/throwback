package app.throwback.guard

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.throwback.R
import app.throwback.core.theme.ThrowbackTheme

/**
 * The guard's full-screen "Use Throwback instead" cover, drawn over the Instagram app (display over
 * other apps) while Throwback comes to the front. It takes all touches, so nothing reaches the app
 * underneath, and goes away as soon as Throwback is on screen.
 */
internal class GuardOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    val showing: Boolean get() = view != null

    fun show(): Boolean {
        if (view != null) return true
        val owner = OverlayOwner().also { it.start() }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { ThrowbackTheme { Cover() } }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return try {
            wm.addView(v, params)
            view = v
            this.owner = owner
            true
        } catch (e: RuntimeException) {
            // No overlay permission any more (WindowManager.BadTokenException) or the window was refused.
            Log.w(TAG, "could not show the cover", e)
            owner.stop()
            false
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not remove the cover", e)
        }
        owner?.stop()
        owner = null
    }

    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val TAG = "ThrowbackGuard"
    }
}

@androidx.compose.runtime.Composable
private fun Cover() {
    val colors = ThrowbackTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.bg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(R.drawable.ic_throwback_mark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.accentText),
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.guard_cover_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.guard_cover_body),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            textAlign = TextAlign.Center,
        )
    }
}
