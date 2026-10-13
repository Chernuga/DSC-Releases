package com.example.deepseekstandalone

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bridges the web UI to the Android Storage Access Framework.
 *
 * The page cannot open a folder picker itself, so it calls into this object;
 * the activity launches the system picker and the result is held until the page
 * asks for it. Folder access is granted by the user per-folder and is
 * persistent, so a linked workspace survives app restarts without any broad
 * storage permission.
 *
 * Every method is @JavascriptInterface: without the annotation the page cannot
 * see it.
 */
class WorkspaceBridge(private val activity: MainActivity) {

    /** Resolved tree URIs, keyed by the id handed to the page. */
    private val folders = ConcurrentHashMap<String, Uri>()

    /** Set while a picker is open, released when the user chooses or cancels. */
    @Volatile
    private var pickerLatch: CountDownLatch? = null

    @Volatile
    private var pickerResult: Uri? = null

    @Volatile
    private var pickerCancelled: Boolean = false

    companion object {
        const val PICK_FOLDER_REQUEST = 7001
        /** How long the page waits for the user to choose a folder. */
        private const val PICK_TIMEOUT_SECONDS = 300L
    }

    /**
     * Open the folder picker and block until the user finishes.
     *
     * Called from the page's JavaScript thread, so blocking here does not stall
     * the UI. Returns a token the page uses for later reads and writes.
     */
    @JavascriptInterface
    fun pickFolder(): String {
        val latch = CountDownLatch(1)
        pickerLatch = latch
        pickerResult = null
        pickerCancelled = false

        activity.runOnUiThread {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    // Keep the grant across process restarts; without these flags
                    // the folder becomes unreadable after the app is killed.
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
                }
                activity.startActivityForResult(intent, PICK_FOLDER_REQUEST)
            } catch (e: Exception) {
                pickerCancelled = true
                latch.countDown()
            }
        }

        val finished = latch.await(PICK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        pickerLatch = null

        if (!finished || pickerCancelled || pickerResult == null) {
            return jsonError("Folder selection was cancelled.")
        }

        val uri = pickerResult!!
        // Persist the permission so the folder stays usable after a restart.
        try {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Some providers do not offer persistable grants; the folder still
            // works for this session.
        }

        val id = "ws" + System.currentTimeMillis().toString(36)
        folders[id] = uri

        val doc = DocumentFile.fromTreeUri(activity, uri)
        return """{"ok":true,"id":"$id","name":${jsonString(doc?.name ?: "Folder")},"uri":${jsonString(uri.toString())}}"""
    }

    /** Re-attach to a folder the page remembered, after a restart. */
    @JavascriptInterface
    fun restoreFolder(id: String, uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            val doc = DocumentFile.fromTreeUri(activity, uri)
            if (doc == null || !doc.canRead()) {
                """{"ok":false,"error":"The folder is no longer accessible."}"""
            } else {
                folders[id] = uri
                """{"ok":true,"id":"$id","name":${jsonString(doc.name ?: "Folder")}}"""
            }
        } catch (e: Exception) {
            jsonError("Could not restore the folder: ${e.message}")
        }
    }

    /** List every file in the linked folder, recursively, as relative paths. */
    @JavascriptInterface
    fun listFiles(id: String): String {
        val tree = folders[id] ?: return jsonError("No folder is linked.")
        return try {
            val root = DocumentFile.fromTreeUri(activity, tree)
                ?: return jsonError("The folder could not be opened.")
            val out = StringBuilder("[")
            var first = true
            fun walk(dir: DocumentFile, prefix: String) {
                for (child in dir.listFiles()) {
                    val name = child.name ?: continue
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (child.isDirectory) {
                        walk(child, path)
                    } else {
                        if (!first) out.append(',')
                        first = false
                        out.append(
                            """{"path":${jsonString(path)},"size":${child.length()}}"""
                        )
                    }
                }
            }
            walk(root, "")
            out.append("]")
            """{"ok":true,"files":$out}"""
        } catch (e: Exception) {
            jsonError("Could not list the folder: ${e.message}")
        }
    }

    /** Read one file, returned base64 so any content survives the bridge. */
    @JavascriptInterface
    fun readFile(id: String, relativePath: String): String {
        val tree = folders[id] ?: return jsonError("No folder is linked.")
        return try {
            val file = findFile(tree, relativePath)
                ?: return jsonError("No such file: $relativePath")
            val bytes = activity.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: return jsonError("Could not open $relativePath")
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            """{"ok":true,"base64":${jsonString(b64)},"size":${bytes.size}}"""
        } catch (e: Exception) {
            jsonError("Could not read $relativePath: ${e.message}")
        }
    }

    /**
     * Write a file, creating parent folders as needed.
     *
     * Content arrives base64-encoded: the bridge passes strings, and text-only
     * handling would corrupt anything binary.
     */
    @JavascriptInterface
    fun writeFile(id: String, relativePath: String, base64: String): String {
        val tree = folders[id] ?: return jsonError("No folder is linked.")
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val parts = relativePath.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return jsonError("Empty path.")

            var dir = DocumentFile.fromTreeUri(activity, tree)
                ?: return jsonError("The folder could not be opened.")
            // Create or descend through every intermediate directory.
            for (i in 0 until parts.size - 1) {
                val segment = parts[i]
                dir = dir.findFile(segment) ?: dir.createDirectory(segment)
                    ?: return jsonError("Could not create folder: $segment")
            }

            val fileName = parts.last()
            val existing = dir.findFile(fileName)
            val target = existing ?: dir.createFile("application/octet-stream", fileName)
                ?: return jsonError("Could not create file: $fileName")

            activity.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
                ?: return jsonError("Could not write $relativePath")

            """{"ok":true,"path":${jsonString(relativePath)},"size":${bytes.size}}"""
        } catch (e: Exception) {
            jsonError("Could not write $relativePath: ${e.message}")
        }
    }

    /** Delete a file inside the linked folder. */
    @JavascriptInterface
    fun deleteFile(id: String, relativePath: String): String {
        val tree = folders[id] ?: return jsonError("No folder is linked.")
        return try {
            val file = findFile(tree, relativePath)
                ?: return jsonError("No such file: $relativePath")
            if (file.delete()) """{"ok":true}""" else jsonError("Could not delete $relativePath")
        } catch (e: Exception) {
            jsonError("Could not delete $relativePath: ${e.message}")
        }
    }

    /** Called by the activity when the picker returns. */
    fun onFolderPicked(uri: Uri?, cancelled: Boolean) {
        pickerResult = uri
        pickerCancelled = cancelled || uri == null
        pickerLatch?.countDown()
    }

    /** Walk a relative path to its document. */
    private fun findFile(tree: Uri, relativePath: String): DocumentFile? {
        var dir = DocumentFile.fromTreeUri(activity, tree) ?: return null
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        for ((index, segment) in parts.withIndex()) {
            val child = dir.findFile(segment) ?: return null
            if (index == parts.size - 1) return child
            if (!child.isDirectory) return null
            dir = child
        }
        return null
    }

    private fun jsonError(message: String) =
        """{"ok":false,"error":${jsonString(message)}}"""

    /** Minimal JSON string escaping; the bridge deals in JSON text. */
    private fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.append("\"").toString()
    }
}
