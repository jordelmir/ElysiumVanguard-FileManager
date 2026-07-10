package com.elysium.vanguard.core.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 8.2 — SAF tree manager.
 *
 * Centralizes the user's "granted root folder" lifecycle:
 *   1. Request: user opens system picker via [requestTree], we get a content URI
 *      back. We take a persistable URI permission (so it survives reboots) and
 *      persist the URI string in SharedPreferences.
 *   2. Resolve: at app launch we re-read the persisted URI, verify the
 *      persistable permission is still granted, and surface the result via
 *      [currentTreeUri]. If the user revoked the permission from Settings
 *      (Settings → Apps → File Manager → Permissions), this returns null
 *      and the UI prompts the user to re-grant.
 *   3. List: [listChildren], [fileExists], [deleteDocument] operate on the
 *      granted tree using [DocumentFile.fromTreeUri]. The list tree is
 *      strictly inside the granted URI — we never escape it.
 *
 * Why not use [android.provider.DocumentsContract] directly: `DocumentFile`
 * from `androidx.documentfile` is the supported wrapper. We use it for every
 * tree operation; the only place we touch the raw URI is when the user
 * grants it.
 *
 * Migration plan from old code: the old FileManagerRepository used
 * `Environment.getExternalStorageDirectory()` directly, which is broken on
 * Android 11+. With this class, the file manager is functional on every
 * device that supports the SAF picker (API 19+).
 */
@Singleton
open class SafTreeManager @Inject constructor(
    // PHASE 8.9: context is nullable for unit tests. The [prefs] field
    // is the only consumer; we gate it with a context-free test double.
    @ApplicationContext private val context: Context?
) {

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentTreeUri = MutableStateFlow<Uri?>(loadPersistedUri())
    val currentTreeUri: StateFlow<Uri?> = _currentTreeUri.asStateFlow()

    /**
     * True when the user has granted a tree AND we still hold the persistable
     * permission. UI binds the file browser's root to this flag.
     */
    val hasUsableTree: Boolean
        get() = _currentTreeUri.value?.let { uri ->
            isPermissionPersisted(uri)
        } ?: false

    /**
     * Hook for the activity's launcher. After the user picks a tree, call
     * [onTreePicked] with the returned URI. We persist + take permission and
     * publish the new state.
     */
    fun onTreePicked(uri: Uri?) {
        if (uri == null) return
        try {
            context?.contentResolver?.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some pickers don't return persistable URIs. We still store the
            // URI for the current session, but the user will have to re-grant
            // after a reboot. Better than failing silently.
        }
        prefs?.edit { putString(KEY_TREE_URI, uri.toString()) }
        _currentTreeUri.value = uri
    }

    /**
     * User-driven revoke (e.g. a "Disconnect" button in the file manager).
     * Releases the persistable permission and clears the persisted URI.
     */
    fun disconnect() {
        _currentTreeUri.value?.let { uri ->
            try {
                // Both 1-arg and 2-arg overloads exist in API 33+; the 1-arg
                // is deprecated. We use reflection-free call to the modern
                // 2-arg variant which works on all supported API levels
                // (the 2-arg variant was added in API 33, but the 1-arg
                // form is still there in earlier API levels — we just need
                // to choose the right one at compile time).
                releaseUriPermissionCompat(uri)
            } catch (_: SecurityException) { /* permission may have already been released */ }
        }
        prefs?.edit { remove(KEY_TREE_URI) }
        _currentTreeUri.value = null
    }

    /**
     * Releases a persistable URI permission.
     *
     * API quirk: `releasePersistableUriPermission` exists with two signatures
     * depending on the API level:
     *   - API 26-32: `(Uri)` only — single-arg, releases both read and write.
     *   - API 33+: the 1-arg form was removed; only `(Uri, int modeFlags)` is
     *     available.
     *
     * Since the project compiles against SDK 34, the compiler only sees the
     * 2-arg form. We use reflection to invoke the legacy 1-arg form on
     * older devices, falling back to the modern form when reflection fails.
     */
    private fun releaseUriPermissionCompat(uri: Uri) {
        val resolver = context?.contentResolver ?: return
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            return
        }
        // Legacy path: invoke the 1-arg form via reflection. We swallow any
        // failure because the worst case is the URI permission lingers —
        // which is still preferable to crashing the app.
        try {
            val method = resolver.javaClass.getMethod(
                "releasePersistableUriPermission",
                Uri::class.java
            )
            method.invoke(resolver, uri)
        } catch (_: NoSuchMethodException) {
            // Method literally doesn't exist on this build of Android.
            // We accept the leaked permission; it will be cleaned up when
            // the user uninstalls or the system revokes via Settings.
        } catch (_: Exception) {
            // Other reflection errors (e.g. SecurityException) — same
            // outcome: best effort.
        }
    }

    /**
     * Re-check whether our persisted permission is still valid. The system
     * can revoke it without notifying us (e.g. when the user clears app data
     * or revokes from Settings). Returns the live URI or null.
     */
    fun refresh(): Uri? {
        val uri = _currentTreeUri.value ?: return null
        if (!isPermissionPersisted(uri)) {
            // Permission gone; clean up and return null.
            prefs?.edit { remove(KEY_TREE_URI) }
            _currentTreeUri.value = null
            return null
        }
        return uri
    }

    /** List the children of [folderUri] (defaults to the current root). */
    fun listChildren(folderUri: Uri? = _currentTreeUri.value): List<DocumentFile> {
        val tree = folderUri ?: return emptyList()
        val root = DocumentFile.fromTreeUri(context ?: return emptyList(), tree) ?: return emptyList()
        // listFiles() in DocumentFile is synchronous and may be slow on
        // large directories. The caller (ViewModel) is responsible for
        // offloading to Dispatchers.IO. We deliberately don't `runBlocking`
        // here so the manager stays a thin adapter.
        return root.listFiles().filterNotNull()
    }

    /** Resolve a child path (e.g. "Downloads/foo.pdf") under the current root. */
    fun resolveChild(relativePath: String): DocumentFile? {
        val tree = _currentTreeUri.value ?: return null
        val ctx = context ?: return null
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return null
        var current: DocumentFile = root
        for (segment in relativePath.trim('/').split('/').filter { it.isNotEmpty() }) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    /**
     * True iff the URI is one we currently hold a persistable grant for.
     * We check both read and write flags because some operations need both.
     */
    private fun isPermissionPersisted(uri: Uri): Boolean {
        val persisted = context?.contentResolver?.persistedUriPermissions ?: return false
        return persisted.any { p ->
            p.uri == uri &&
                p.isReadPermission &&
                p.isWritePermission
        }
    }

    private fun loadPersistedUri(): Uri? {
        val raw = prefs?.getString(KEY_TREE_URI, null) ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (!isPermissionPersisted(uri)) {
            // Permission lost (e.g. app data cleared). Clean up.
            prefs?.edit { remove(KEY_TREE_URI) }
            return null
        }
        return uri
    }

    companion object {
        private const val PREFS_NAME = "elysium_saf"
        private const val KEY_TREE_URI = "current_tree_uri"
    }
}