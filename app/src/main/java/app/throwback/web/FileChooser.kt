package app.throwback.web

import android.content.Intent
import android.net.Uri

/** What a web `<input type="file" accept=...>` asks for, so photos and videos use Android's photo picker. */
internal enum class PickKind {
    IMAGES, VIDEOS, IMAGES_AND_VIDEOS,

    /** Anything else (documents, no accept list): the generic file chooser. */
    OTHER,
}

internal object FileChooserKinds {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "3gp", "mkv")

    /**
     * @param acceptTypes WebChromeClient.FileChooserParams.acceptTypes: MIME types and/or
     *        ".ext" suffixes; entries may themselves be comma-separated.
     */
    fun classify(acceptTypes: Array<String>?): PickKind {
        val types = acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (types.isEmpty()) return PickKind.OTHER
        var images = false
        var videos = false
        for (t in types) {
            val extension = if (t.startsWith(".")) t.substring(1) else null
            when {
                t.startsWith("image/") || extension in IMAGE_EXTENSIONS -> images = true
                t.startsWith("video/") || extension in VIDEO_EXTENSIONS -> videos = true
                else -> return PickKind.OTHER
            }
        }
        return when {
            images && videos -> PickKind.IMAGES_AND_VIDEOS
            images -> PickKind.IMAGES
            else -> PickKind.VIDEOS
        }
    }

    /** All URIs a document picker returned, including multi-select clip data. */
    fun urisFrom(resultOk: Boolean, data: Intent?): Array<Uri>? {
        if (!resultOk || data == null) return null
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty()) data.data?.let(uris::add)
        return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
