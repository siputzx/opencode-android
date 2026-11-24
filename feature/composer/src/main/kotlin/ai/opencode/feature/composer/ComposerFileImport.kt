package ai.opencode.feature.composer

import ai.opencode.core.files.ComposerFileStaging
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.files.ComposerStagedFile
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Where a composer file comes from. Every source ends as a private staged copy the upload streams
 * from. Pickers, drops and transcript files all arrive as [Uri]s.
 */
sealed interface ComposerFileSource {
    /** From the Files picker (`ACTION_OPEN_DOCUMENT`) or a dropped file. */
    data class Url(val uri: Uri) : ComposerFileSource

    /**
     * A video picked in the system photo picker. The staged file takes [id], so the picker can find
     * its chip again when the video is deselected.
     */
    data class PhotosPickerItem(val uri: Uri, val id: UUID = UUID.randomUUID()) : ComposerFileSource
}

object ComposerFileRouting {
    private val inlineImageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif")
    private val inlineImageMimes = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/heic", "image/heif")

    /**
     * Image files from Files keep the inline image path (re-encoded like photos); everything else
     * uploads. A content URI has no extension, so its display name or MIME type decides.
     */
    fun isInlineImage(name: String, mime: String? = null): Boolean {
        val type = mime?.lowercase(Locale.ROOT)
        if (type != null && type in inlineImageMimes) return true
        return AttachmentFileType.fileExtension(name) in inlineImageExtensions
    }

    fun isInlineImage(uri: Uri, context: Context = dependency(AppContext)): Boolean =
        isInlineImage(ComposerContentInfo.displayName(context, uri) ?: uri.lastPathSegment ?: "", context.contentResolver.getType(uri))
}

/**
 * Stages picked files off the main thread. File imports don't supersede one another. [isLoading] is
 * Compose state.
 */
class ComposerFileImporter(private val scope: CoroutineScope) {
    private var active by mutableIntStateOf(0)

    val isLoading: Boolean get() = active > 0

    fun start(
        sources: List<ComposerFileSource>,
        onStaged: (List<ComposerStagedFile>) -> Unit,
        onFailure: (Int) -> Unit,
    ) {
        if (sources.isEmpty()) return
        active += 1
        scope.launch(Dispatchers.Main) {
            val staged = mutableListOf<ComposerStagedFile>()
            var failures = 0
            for (source in sources) {
                val file = stage(source)
                if (file != null) staged += file else failures += 1
            }
            active -= 1
            if (staged.isNotEmpty()) onStaged(staged)
            if (failures > 0) onFailure(failures)
        }
    }

    private suspend fun stage(source: ComposerFileSource): ComposerStagedFile? = withContext(Dispatchers.IO) {
        when (source) {
            is ComposerFileSource.Url -> runCatching { ComposerUriStaging.stage(source.uri) }.getOrNull()
            is ComposerFileSource.PhotosPickerItem -> runCatching { ComposerUriStaging.stage(source.uri, id = source.id) }.getOrNull()
        }
    }
}

/** A content URI's display name and size (`OpenableColumns`). */
internal object ComposerContentInfo {
    fun displayName(context: Context, uri: Uri): String? = query(context, uri, OpenableColumns.DISPLAY_NAME) { getString(it) }

    fun size(context: Context, uri: Uri): Long? = query(context, uri, OpenableColumns.SIZE) { if (isNull(it)) null else getLong(it) }

    private fun <T> query(context: Context, uri: Uri, column: String, read: android.database.Cursor.(Int) -> T?): T? {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: return null
            @Suppress("UNCHECKED_CAST")
            return when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name as T
                OpenableColumns.SIZE -> file.length() as T
                else -> null
            }
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(column)
                if (index >= 0 && cursor.moveToFirst()) cursor.read(index) else null
            }
        }.getOrNull()
    }
}

/** Copies a content `Uri` into `:core`'s [ComposerFileStaging] folder, which does the rest. */
object ComposerUriStaging {
    /**
     * Copies [source] into a fresh per-file directory that keeps the original name. Blocking:
     * call off the main thread.
     */
    fun stage(source: Uri, name: String? = null, id: UUID = UUID.randomUUID(), context: Context = dependency(AppContext)): ComposerStagedFile {
        val resolver = context.contentResolver
        val fileName = AttachmentFileType.sanitizedName(
            name ?: ComposerContentInfo.displayName(context, source) ?: source.lastPathSegment ?: "file",
        )
        val folder = File(ComposerFileStaging.directory, id.toString().uppercase(Locale.ROOT))
        if (!folder.isDirectory && !folder.mkdirs()) throw java.io.IOException("Couldn’t create the staging folder.")
        val destination = File(folder, fileName)
        try {
            val input = resolver.openInputStream(source) ?: throw java.io.IOException("The file couldn’t be opened.")
            input.use { stream -> destination.outputStream().use { stream.copyTo(it) } }
        } catch (error: Throwable) {
            folder.deleteRecursively()
            throw error
        }
        val declared = resolver.getType(source)?.takeIf { it != "application/octet-stream" }
        val head = runCatching {
            destination.inputStream().use { stream ->
                val buffer = ByteArray(4096)
                var total = 0
                while (total < buffer.size) {
                    val count = stream.read(buffer, total, buffer.size - total)
                    if (count < 0) break
                    total += count
                }
                buffer.copyOf(total)
            }
        }.getOrDefault(ByteArray(0))
        return ComposerStagedFile(
            id = id,
            name = fileName,
            mime = AttachmentFileType.mime(fileName, declared, head),
            size = destination.length(),
            url = destination,
        )
    }
}

/**
 * The system pickers behind the + menu's Files and Photos. Activity results need an Activity, so
 * the screen launches these intents and routes the result [uris].
 */
object ComposerPickers {
    /**
     * The system photo picker, up to [maxItems]. Videos upload as files, so they appear only
     * when the server takes uploads ([includesVideos]). Needs no storage permission.
     */
    fun photosIntent(maxItems: Int = AttachmentPickerButton.MAX_PHOTO_SELECTION, includesVideos: Boolean): Intent {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES)
        if (maxItems > 1) intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxItems)
        if (!includesVideos) intent.type = "image/*"
        return intent
    }

    /** The picked URIs of an `ACTION_OPEN_DOCUMENT` / `ACTION_PICK_IMAGES` result, in order. */
    fun uris(resultCode: Int, data: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || data == null) return emptyList()
        val clip: ClipData? = data.clipData
        if (clip != null && clip.itemCount > 0) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return listOfNotNull(data.data)
    }

    /** A picked item whose type is a movie. */
    fun isVideo(uri: Uri, context: Context = dependency(AppContext)): Boolean =
        context.contentResolver.getType(uri)?.startsWith("video/") == true
}
