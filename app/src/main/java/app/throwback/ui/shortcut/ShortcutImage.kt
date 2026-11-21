package app.throwback.ui.shortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import kotlin.math.max
import kotlin.math.min

/**
 * The user's own picture as a shortcut icon: an adaptive icon's full layer (108 dp, at the highest
 * density), with the picture, cropped square, filling the middle 72 dp that launchers show through
 * their mask. The edge beyond (only seen when a launcher animates the icon) continues the picture.
 * Transparent pictures (logos) go on white, since a launcher would show its wallpaper through them.
 */
internal object ShortcutImage {
    const val SIZE = 432 // 108 dp at xxxhdpi
    private const val SHOWN = SIZE * 2 / 3 // the middle 72 dp
    private const val DECODE_MAX = 1024 // a big photo is decoded at most this size: memory

    /** Null when the picture can't be read (gone, unreadable, not an image). Call off the main thread. */
    fun load(context: Context, uri: Uri): Bitmap? = try {
        decode(context, uri)?.let(::layer)
    } catch (e: Exception) { // SecurityException (access ended), IOException, decoder errors
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun layer(src: Bitmap): Bitmap {
        val side = min(src.width, src.height)
        val crop = Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val m = (SIZE - SHOWN) / 2
        val shown = Rect(m, m, m + SHOWN, m + SHOWN)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, crop, Rect(0, 0, SIZE, SIZE), paint) // the hidden edge
            drawRect(shown, Paint().apply { color = Color.WHITE })
            drawBitmap(src, crop, shown, paint)
        }
        src.recycle()
        return out
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > DECODE_MAX) {
                    decoder.setTargetSize(info.size.width * DECODE_MAX / longest, info.size.height * DECODE_MAX / longest)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // drawn on a Canvas below
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECODE_MAX) sample *= 2
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
