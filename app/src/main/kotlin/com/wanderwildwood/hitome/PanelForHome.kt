package com.wanderwildwood.hitome

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The panel's lines for a home screen: inkOS:wndr (com.wanderwildwood.iori), which draws them
 * under its apps. `content://com.wanderwildwood.hitome.panel/lines`, read-only.
 *
 * It hands over what the lock screen would show from the apps that take part - the same
 * sources, in the order chosen here, with the same switches here - and, for each source, why it
 * shows or does not. Other apps' notifications are never part of it.
 *
 * Columns, one row per line: `pkg` (the source, from [Glance.SOURCES]), `heading` (on a
 * source's first row, optional), `lead` (optional), `text`, `bold` (1 or 0), and `status`. A
 * source with no lines has one row of its own, with no text, to carry its status:
 *
 * - [ON]: its lines follow. Today's events read by Glance itself, on a phone without Calendar,
 *   are Calendar's, and may be on with nothing left today.
 * - [OFF_APP]: the app answered with nothing - its own lock-screen switch is off, or it has
 *   nothing to say just now. The apps answer both the same way.
 * - [OFF_GLANCE]: one that stays off until switched on in Glance too, and is not.
 * - [NOT_INSTALLED].
 * - [NEEDS_UPDATE]: installed, but too old to hand Glance its lines.
 * - [NO_ANSWER]: it refused, failed, or took longer than [WAIT_MS].
 *
 * It answers only iori, known by its package name and the certificate its releases are signed
 * with ([IORI_CERT_SHA256]), so an app that took the same name, or a debug build, gets nothing.
 * The apps are read from here, as Glance, so their own providers keep answering Glance alone.
 */
class PanelForHome : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val context = context ?: return null
        if (uri.path != "/lines") return null
        if (!allowed(context, Binder.getCallingUid())) return null
        val identity = Binder.clearCallingIdentity()
        return try {
            MatrixCursor(COLUMNS).apply { rows(context).forEach { addRow(it) } }
        } catch (_: Exception) {
            null
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /** Every source in the order chosen here, each with its status, as [query]'s rows. */
    private fun rows(context: Context): List<Array<Any?>> {
        val calendarInstalled = Glance.calendarInstalled(context)
        val sources = Glance.sources(Glance.order(context))
        // Each app is asked on its own thread, all at once, so one that hangs costs only itself.
        val asked = sources.associateWith { pkg -> pool.submit<Answer> { answer(context, pkg, calendarInstalled) } }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        return sources.flatMap { pkg -> rowsFor(pkg, waitFor(asked.getValue(pkg), deadline)) }
    }

    private fun waitFor(future: Future<Answer>, deadline: Long): Answer = try {
        future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
    } catch (_: Exception) {
        future.cancel(true)
        Answer(NO_ANSWER)
    }

    /** What [pkg] has to say, as [Glance.readAll] would read it, and why. */
    private fun answer(context: Context, pkg: String, calendarInstalled: Boolean): Answer {
        val pm = context.packageManager
        if (pkg == Glance.CALENDAR && !calendarInstalled) {
            // Glance's own reading of today takes Calendar's place, as on the lock screen.
            if (!Today.enabled(context)) return Answer(NOT_INSTALLED)
            return Answer(ON, Today.read(context))
        }
        val installed = try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return Answer(NOT_INSTALLED)
        if (pkg in Glance.OPT_IN && !Glance.switchedOn(context, pkg)) return Answer(OFF_GLANCE)
        if (pm.resolveContentProvider("$pkg.glance", 0) == null) return Answer(NEEDS_UPDATE)
        return try {
            context.contentResolver.query(Glance.uri(pkg), Glance.COLUMNS, null, null, null)?.use { c ->
                val section = Glance.section(c, pkg)
                if (section == null) Answer(OFF_APP) else Answer(ON, section)
            } ?: Answer(NO_ANSWER)
        } catch (_: Exception) {
            Answer(NO_ANSWER)
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    /** A source's [status], and its lines when it has any. */
    data class Answer(val status: String, val section: Glance.Section? = null)

    companion object {
        const val AUTHORITY = "com.wanderwildwood.hitome.panel"

        /** The one app answered. */
        const val IORI = "com.wanderwildwood.iori"

        /**
         * The SHA-256 of the certificate iori's releases are signed with, lower-case hex with no
         * colons (`keytool -list -v` on its release keystore). Debug builds are not answered.
         */
        const val IORI_CERT_SHA256 = "fb06881da0a2c7b0a98fc6c730098a106636974db877d63e18670b53b0890512"

        val COLUMNS = arrayOf("pkg", "heading", "lead", "text", "bold", "status")

        const val ON = "on"
        const val OFF_APP = "off_app"
        const val OFF_GLANCE = "off_glance"
        const val NOT_INSTALLED = "not_installed"
        const val NEEDS_UPDATE = "needs_update"
        const val NO_ANSWER = "no_answer"

        /** How long all the apps together may take; home asks again on its next look. */
        const val WAIT_MS = 1000L

        private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "panel-for-home").apply { isDaemon = true }
        }

        /** [pkg]'s rows: one per line, or one with no text that carries only its status. */
        fun rowsFor(pkg: String, answer: Answer): List<Array<Any?>> {
            val section = answer.section
            if (section == null || section.lines.isEmpty()) return listOf(arrayOf(pkg, null, null, null, 0, answer.status))
            return section.lines.mapIndexed { i, line ->
                arrayOf(pkg, if (i == 0) section.heading else null, line.lead, line.text, if (line.bold) 1 else 0, answer.status)
            }
        }

        /** Whether [uid] is iori, signed by its release certificate and nothing else. */
        private fun allowed(context: Context, uid: Int): Boolean {
            val pm = context.packageManager
            val packages = pm.getPackagesForUid(uid) ?: return false
            return packages.any { name ->
                if (name != IORI) return@any false
                val signers = runCatching {
                    pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
                }.getOrNull()
                !signers.isNullOrEmpty() && signers.all { sha256(it.toByteArray()) == IORI_CERT_SHA256 }
            }
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
