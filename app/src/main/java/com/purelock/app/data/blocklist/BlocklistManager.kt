package com.purelock.app.data.blocklist

import android.content.Context
import com.purelock.app.data.db.AppDatabase
import com.purelock.app.data.db.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Holds the adult-domain filter in memory for fast lookups from the accessibility URL-watch.
 *
 * Sources, all local: a small bundled seed list (assets), an optional downloaded list (see
 * [updateFromRemote]) and the user's own blacklist/allowlist. The user's allowlist always wins.
 * Lookups never leave the device.
 */
class BlocklistManager(private val context: Context, private val db: AppDatabase) {

    @Volatile private var listDomains: Set<String> = emptySet()
    @Volatile private var customBlocked: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()

    private val remoteFile get() = File(context.filesDir, REMOTE_FILE)

    fun isBlocked(host: String): Boolean {
        if (DomainMatcher.matches(host, allowed)) return false
        return DomainMatcher.matches(host, customBlocked) || DomainMatcher.matches(host, listDomains)
    }

    /** Number of domains in the bundled + downloaded list (not counting the user's own entries). */
    fun listSize(): Int = listDomains.size

    suspend fun reload() = withContext(Dispatchers.IO) {
        val merged = HashSet<String>(8_192)
        runCatching {
            context.assets.open(SEED_ASSET).bufferedReader().useLines { lines ->
                lines.forEach { l -> DomainMatcher.parseListLine(l)?.let(merged::add) }
            }
        }
        val remote = remoteFile
        if (remote.exists()) {
            runCatching {
                remote.bufferedReader().useLines { lines ->
                    lines.forEach { l -> DomainMatcher.parseListLine(l)?.let(merged::add) }
                }
            }
        }
        listDomains = merged
        customBlocked = db.blockedDomainDao().getAll().map { it.domain }.toHashSet()
        allowed = db.allowedDomainDao().getAll().map { it.domain }.toHashSet()
    }

    /** Last successful list update: (epoch millis, number of domains) or null if never updated. */
    suspend fun lastUpdate(): Pair<Long, Int>? {
        val at = db.prefDao().get(KEY_UPDATED_AT)?.toLongOrNull() ?: return null
        val count = db.prefDao().get(KEY_COUNT)?.toIntOrNull() ?: 0
        return at to count
    }

    /**
     * Downloads a hosts-format list over HTTPS and stores only the domains. This is the only
     * network request the filter makes and it carries no user data: it is a plain GET of a public file.
     */
    suspend fun updateFromRemote(url: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("https://")) { "Only https:// sources are allowed" }
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "PureLock")
            try {
                check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                val tmp = File(context.filesDir, "$REMOTE_FILE.tmp")
                var count = 0
                var bytes = 0L
                tmp.bufferedWriter().use { out ->
                    connection.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            bytes += line.length + 1
                            check(bytes <= MAX_DOWNLOAD_BYTES) { "List is too large" }
                            val domain = DomainMatcher.parseListLine(line) ?: continue
                            out.write(domain)
                            out.newLine()
                            count++
                        }
                    }
                }
                check(count > 0) { "The list contained no domains" }
                check(tmp.renameTo(remoteFile) || run { remoteFile.delete(); tmp.renameTo(remoteFile) }) {
                    "Could not save the list"
                }
                db.prefDao().set(UserPreferences(KEY_UPDATED_AT, System.currentTimeMillis().toString()))
                db.prefDao().set(UserPreferences(KEY_COUNT, count.toString()))
                reload()
                count
            } finally {
                connection.disconnect()
            }
        }
    }

    fun deleteDownloadedList() {
        remoteFile.delete()
    }

    private companion object {
        const val SEED_ASSET = "adult_domains.txt"
        const val REMOTE_FILE = "blocklist_remote.txt"
        const val KEY_UPDATED_AT = "filter_updated_at"
        const val KEY_COUNT = "filter_count"
        const val MAX_DOWNLOAD_BYTES = 30L * 1024 * 1024
    }
}
