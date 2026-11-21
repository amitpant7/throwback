package app.throwback.connect.guard

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
import androidx.compose.runtime.Composable
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

    /**
     * @param storyTimeUp the story pass ran out without a post: the cover says so, with a wink.
     * @param windowHint Instagram stayed on screen under the last cover (split screen, a pop-up
     *        window, picture-in-picture): the cover says to close that window.
     */
    fun show(storyTimeUp: Boolean = false, windowHint: Boolean = false): Boolean {
        if (view != null) return true
        val owner = OverlayOwner().also { it.start() }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { ThrowbackTheme { Cover(storyTimeUp, windowHint) } }
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

@Composable
private fun Cover(storyTimeUp: Boolean, windowHint: Boolean) {
    val colors = ThrowbackTheme.colors
    val (title, body) = when {
        windowHint -> R.string.guard_cover_window_title to R.string.guard_cover_window_body
        storyTimeUp -> R.string.guard_cover_story_title to R.string.guard_cover_story_body
        else -> R.string.guard_cover_title to R.string.guard_cover_body
    }
    Column(
        Modifier.fillMaxSize().background(colors.bg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(R.drawable.tb_logo),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            textAlign = TextAlign.Center,
        )
    }
}
