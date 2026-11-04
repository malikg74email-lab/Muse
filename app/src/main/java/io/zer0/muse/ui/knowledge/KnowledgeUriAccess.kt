package io.zer0.muse.ui.knowledge

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.zer0.common.Logger

/**
 * Preserve the read grant for a document selected through ACTION_OPEN_DOCUMENT.
 *
 * Re-indexing may happen after the importing screen or process has gone away;
 * without a persistable grant, a stored content URI is only a temporary handle.
 */
internal fun persistKnowledgeUriReadPermission(context: Context, uri: Uri): Boolean = runCatching {
    context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
    true
}.onFailure {
    // Some providers do not expose persistable grants; the current import can still proceed.
    Logger.w("KnowledgeUriAccess", "Unable to persist document read permission: $uri", it)
}.getOrDefault(false)
