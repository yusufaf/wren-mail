package dev.yusufaf.wren.data

import dev.yusufaf.wren.account.Account
import dev.yusufaf.wren.mailkit.Envelope
import dev.yusufaf.wren.mailkit.MailService
import dev.yusufaf.wren.mailkit.MessageDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cache-first facade over [MailService]. The inbox renders from Room and is
 * updated by [refresh]; triage actions apply to the cache immediately, queue a
 * [PendingOp], and reach the server on the next flush (immediate best-effort,
 * otherwise the periodic sync worker retries).
 */
class MailRepository(
    private val db: WrenDatabase,
    private val mailService: MailService,
) {
    // Serializes flushes so a UI-triggered flush and the sync worker can't
    // send the same pending op twice.
    private val flushMutex = Mutex()

    val inbox: Flow<List<Envelope>> = db.inboxDao().envelopes().map { cached ->
        cached.map { Envelope(it.uid, it.sender, it.subject, it.date, it.unread, it.flagged) }
    }

    /** Throws MessagingException (or IOException) when settings are wrong. */
    suspend fun checkSettings(account: Account) = mailService.checkSettings(account)

    /** Flushes pending ops, then replaces the cache with the live inbox. Throws on failure. */
    suspend fun refresh(account: Account) {
        flushPendingOps(account)
        val envelopes = mailService.fetchInbox(account)
        db.inboxDao().replaceAll(
            envelopes.mapIndexed { index, e ->
                CachedEnvelope(e.uid, e.sender, e.subject, e.date, e.unread, e.flagged, index)
            },
        )
    }

    /** Live fetch (no body cache in v1); mirrors the server-side mark-read into the cache. */
    suspend fun fetchMessage(account: Account, uid: String): MessageDetail {
        val detail = mailService.fetchMessage(account, uid)
        db.inboxDao().setUnread(uid, false)
        return detail
    }

    suspend fun archive(account: Account, uid: String) {
        db.inboxDao().remove(uid)
        enqueue(account, PendingOp(uid = uid, type = PendingOp.ARCHIVE))
    }

    suspend fun delete(account: Account, uid: String) {
        db.inboxDao().remove(uid)
        enqueue(account, PendingOp(uid = uid, type = PendingOp.DELETE))
    }

    suspend fun setFlagged(account: Account, uid: String, flagged: Boolean) {
        db.inboxDao().setFlagged(uid, flagged)
        enqueue(account, PendingOp(uid = uid, type = PendingOp.SET_FLAGGED, value = flagged))
    }

    suspend fun setUnread(account: Account, uid: String, unread: Boolean) {
        db.inboxDao().setUnread(uid, unread)
        enqueue(account, PendingOp(uid = uid, type = PendingOp.SET_SEEN, value = !unread))
    }

    private suspend fun enqueue(account: Account, op: PendingOp) {
        db.pendingOpDao().insert(op)
        // Best effort: offline just leaves the op queued for the sync worker.
        runCatching { flushPendingOps(account) }
    }

    /**
     * Sends queued ops FIFO, removing each on success. Stops and throws on the
     * first failure so the remainder is retried later. The IMAP ops are safe to
     * repeat: UID commands on a message that is already gone no-op server-side.
     */
    suspend fun flushPendingOps(account: Account) {
        flushMutex.withLock {
            for (op in db.pendingOpDao().all()) {
                when (op.type) {
                    PendingOp.ARCHIVE -> mailService.archiveMessage(account, op.uid)
                    PendingOp.DELETE -> mailService.deleteMessage(account, op.uid)
                    PendingOp.SET_FLAGGED -> mailService.setFlagged(account, op.uid, op.value)
                    PendingOp.SET_SEEN -> mailService.setUnread(account, op.uid, !op.value)
                }
                db.pendingOpDao().delete(op.id)
            }
        }
    }
}
