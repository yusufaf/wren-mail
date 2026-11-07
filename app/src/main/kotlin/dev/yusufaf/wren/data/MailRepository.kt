package dev.yusufaf.wren.data

import dev.yusufaf.wren.account.Account
import dev.yusufaf.wren.mailkit.Envelope
import dev.yusufaf.wren.mailkit.MailOperations
import dev.yusufaf.wren.mailkit.MessageDetail
import dev.yusufaf.wren.mailkit.isConnectionLevel
import dev.yusufaf.wren.mailkit.isLoginRejection
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.thunderbird.legacy.logging.Log

/**
 * Cache-first facade over [MailOperations]. The inbox renders from Room and is
 * updated by [refresh]; triage actions apply to the cache immediately, queue a
 * [PendingOp], and reach the server on the next flush (immediate best-effort,
 * otherwise the periodic sync worker retries). [scope] outlives any single
 * screen — it's what lets an archive's undo timer (see [archive]) survive
 * recomposition and navigation. [now] is the wall clock [PendingOp.notBeforeMs]
 * is stamped and compared against; overridable so tests can align it with a
 * `TestScope`'s virtual clock instead of real time, which [scope]'s `delay`
 * runs against under `runTest`.
 */
class MailRepository(
    private val inboxDao: InboxDao,
    private val pendingOpDao: PendingOpDao,
    private val mailService: MailOperations,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Serializes flushes so a UI-triggered flush and the sync worker can't
    // send the same pending op twice.
    private val flushMutex = Mutex()

    // One entry per uid with a live undo window: the delayed job that will
    // commit the archive when it fires. Removal is the source of truth for
    // "is this archive still undoable" — see [archive] and [undoArchive].
    private val pendingArchiveTimers = ConcurrentHashMap<String, Job>()

    val inbox: Flow<List<Envelope>> = inboxDao.envelopes().map { cached ->
        cached.map { Envelope(it.uid, it.sender, it.subject, it.date, it.unread, it.flagged) }
    }

    /** Throws MessagingException (or IOException) when settings are wrong. */
    suspend fun checkSettings(account: Account) = mailService.checkSettings(account)

    /** Closes any pooled connection; called when the app backgrounds. */
    suspend fun releaseConnections() = mailService.releaseConnections()

    /**
     * Flushes pending ops, then replaces the cache with the live inbox, with
     * any op still queued re-applied on top so an action that hasn't reached
     * the server yet doesn't visibly revert. A failed flush leaves its ops
     * queued for the next sync and doesn't stop the fetch, unless the server
     * rejected the login: the fetch would log in with the same credentials,
     * and each failed attempt counts toward a provider lockout. Throws when
     * the inbox fetch fails or the login was rejected.
     */
    suspend fun refresh(account: Account) {
        try {
            flushPendingOps(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isLoginRejection()) throw e
            Log.w(e, "Couldn't flush pending ops; refreshing the inbox anyway")
        }
        // Read after the fetch, so an op queued while it ran is still applied.
        val envelopes = mailService.fetchInbox(account).withQueuedOps(pendingOpDao.due(now()))
        inboxDao.replaceAll(
            envelopes.mapIndexed { index, e ->
                CachedEnvelope(e.uid, e.sender, e.subject, e.date, e.unread, e.flagged, index)
            },
        )
    }

    /** Live fetch (no body cache in v1); mirrors the server-side mark-read into the cache. */
    suspend fun fetchMessage(account: Account, uid: String): MessageDetail {
        val detail = mailService.fetchMessage(account, uid)
        inboxDao.setUnread(uid, false)
        return detail
    }

    /**
     * [undoWindowMs] of zero (the default, and what `MessageScreen` uses)
     * behaves exactly as before: the op is queued and the row is removed from
     * the cache immediately. A positive window instead leaves the cache row
     * in place — `InboxScreen`'s `SwipeToReveal` row renders its own undo
     * affordance, so there is no row left to restore into if we removed it up
     * front. [PendingOp.notBeforeMs] keeps the op itself out of
     * [flushPendingOps] until the window closes, so a concurrent refresh (or
     * the sync worker) can't send it early; the delayed job here is only what
     * turns "due" into "actually sent to the cache and the server" without
     * waiting for the next refresh.
     */
    suspend fun archive(account: Account, uid: String, undoWindowMs: Long = 0L) {
        cancelPendingArchive(uid)
        if (undoWindowMs <= 0L) {
            triage(account, PendingOp(uid = uid, type = PendingOp.ARCHIVE)) {
                inboxDao.remove(uid)
            }
            return
        }
        pendingOpDao.insert(
            PendingOp(
                uid = uid,
                type = PendingOp.ARCHIVE,
                notBeforeMs = now() + undoWindowMs,
            ),
        )
        lateinit var job: Job
        job = scope.launch {
            delay(undoWindowMs)
            // NonCancellable: once the window has elapsed uncancelled, this
            // must finish — a cancelled removal here would leave the op
            // persisted but the cache still showing the message as present.
            withContext(NonCancellable) {
                // Atomic: only the caller that wins this removal (this job,
                // racing undoArchive) commits. The loser is a no-op.
                if (pendingArchiveTimers.remove(uid, job)) {
                    inboxDao.remove(uid)
                    runCatching { flushPendingOps(account) }
                }
            }
        }
        pendingArchiveTimers[uid] = job
    }

    /** Returns false if the undo window already closed (or there wasn't one). */
    fun undoArchive(uid: String): Boolean {
        val job = pendingArchiveTimers.remove(uid) ?: return false
        job.cancel()
        scope.launch { pendingOpDao.deleteByUid(uid) }
        return true
    }

    suspend fun delete(account: Account, uid: String) {
        cancelPendingArchive(uid)
        triage(account, PendingOp(uid = uid, type = PendingOp.DELETE)) {
            inboxDao.remove(uid)
        }
    }

    suspend fun setFlagged(account: Account, uid: String, flagged: Boolean) {
        cancelPendingArchive(uid)
        triage(account, PendingOp(uid = uid, type = PendingOp.SET_FLAGGED, value = flagged)) {
            inboxDao.setFlagged(uid, flagged)
        }
    }

    suspend fun setUnread(account: Account, uid: String, unread: Boolean) {
        cancelPendingArchive(uid)
        triage(account, PendingOp(uid = uid, type = PendingOp.SET_SEEN, value = !unread)) {
            inboxDao.setUnread(uid, unread)
        }
    }

    /**
     * The op is persisted BEFORE the optimistic cache change so a concurrent
     * refresh (which flushes ops first) or a crash between the two writes can
     * never lose the action — the cache change is idempotent and any stale
     * state self-heals on the next refresh. Flushing is best effort: offline
     * just leaves the op queued for the sync worker.
     */
    private suspend fun triage(account: Account, op: PendingOp, applyToCache: suspend () -> Unit) {
        pendingOpDao.insert(op)
        applyToCache()
        runCatching { flushPendingOps(account) }
    }

    /**
     * A pending archive is still just a row sitting in the list — any other
     * triage action taken on it (delete, flag, mark unread, or archiving it
     * again) should win outright rather than race the undo window. Cancels
     * the timer and drops the queued op; a no-op if there is no pending
     * archive for [uid].
     */
    private suspend fun cancelPendingArchive(uid: String) {
        val job = pendingArchiveTimers.remove(uid) ?: return
        job.cancel()
        pendingOpDao.deleteByUid(uid)
    }

    /**
     * Sends due ops in queue order, removing each on success. A
     * connection-level failure (see [isConnectionLevel]) stops the flush and
     * throws, since the rest would fail the same way. An op the server
     * rejects stays queued and is skipped along with every later op for the
     * same message, so per-message order holds while other messages go
     * through; it is retried on every flush, with no attempt cap yet. The
     * IMAP ops are safe to repeat: UID commands on a message that is already
     * gone no-op server-side. An ARCHIVE still inside its undo window is not
     * due yet — see [PendingOp.notBeforeMs] — so this can be called freely
     * (including by the sync worker) without racing an in-progress undo.
     */
    suspend fun flushPendingOps(account: Account) {
        flushMutex.withLock {
            val blockedUids = mutableSetOf<String>()
            for (op in pendingOpDao.due(now())) {
                if (op.uid in blockedUids) continue
                try {
                    send(account, op)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.isConnectionLevel()) throw e
                    Log.w(e, "Server rejected pending ${op.type} for ${op.uid}; keeping it queued")
                    blockedUids += op.uid
                    continue
                }
                // Outside the try, so a database failure is never taken for a server rejection.
                pendingOpDao.delete(op.id)
            }
        }
    }

    private suspend fun send(account: Account, op: PendingOp) {
        when (op.type) {
            PendingOp.ARCHIVE -> mailService.archiveMessage(account, op.uid)
            PendingOp.DELETE -> mailService.deleteMessage(account, op.uid)
            PendingOp.SET_FLAGGED -> mailService.setFlagged(account, op.uid, op.value)
            PendingOp.SET_SEEN -> mailService.setUnread(account, op.uid, !op.value)
        }
    }
}

/** The inbox as the user sees it: [ops] (in queue order) applied on top of the server's state. */
private fun List<Envelope>.withQueuedOps(ops: List<PendingOp>): List<Envelope> {
    if (ops.isEmpty()) return this
    val byUid = associateByTo(LinkedHashMap()) { it.uid }
    for (op in ops) {
        val e = byUid[op.uid] ?: continue
        when (op.type) {
            PendingOp.ARCHIVE, PendingOp.DELETE -> byUid.remove(op.uid)
            PendingOp.SET_FLAGGED -> byUid[op.uid] = e.copy(flagged = op.value)
            PendingOp.SET_SEEN -> byUid[op.uid] = e.copy(unread = !op.value)
        }
    }
    return byUid.values.toList()
}
