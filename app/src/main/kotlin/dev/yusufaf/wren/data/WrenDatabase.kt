package dev.yusufaf.wren.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Cached inbox envelope; [position] preserves server order (newest first). */
@Entity(tableName = "envelopes")
data class CachedEnvelope(
    @PrimaryKey val uid: String,
    val sender: String,
    val subject: String,
    val date: String,
    val unread: Boolean,
    val flagged: Boolean,
    val position: Int,
)

/**
 * A triage action waiting to reach the server. Applied optimistically to the
 * cache when created; removed once the IMAP operation succeeds. [value] is the
 * target state for the flag ops, unused for ARCHIVE/DELETE.
 */
@Entity(tableName = "pending_ops")
data class PendingOp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val type: String,
    val value: Boolean = false,
) {
    companion object {
        const val ARCHIVE = "archive"
        const val DELETE = "delete"
        const val SET_FLAGGED = "set_flagged"
        const val SET_SEEN = "set_seen"
    }
}

@Dao
interface InboxDao {
    @Query("SELECT * FROM envelopes ORDER BY position")
    fun envelopes(): Flow<List<CachedEnvelope>>

    @Transaction
    suspend fun replaceAll(items: List<CachedEnvelope>) {
        clear()
        insertAll(items)
    }

    @Query("DELETE FROM envelopes")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedEnvelope>)

    @Query("UPDATE envelopes SET unread = :unread WHERE uid = :uid")
    suspend fun setUnread(uid: String, unread: Boolean)

    @Query("UPDATE envelopes SET flagged = :flagged WHERE uid = :uid")
    suspend fun setFlagged(uid: String, flagged: Boolean)

    @Query("DELETE FROM envelopes WHERE uid = :uid")
    suspend fun remove(uid: String)
}

@Dao
interface PendingOpDao {
    @Query("SELECT * FROM pending_ops ORDER BY id")
    suspend fun all(): List<PendingOp>

    @Insert
    suspend fun insert(op: PendingOp)

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [CachedEnvelope::class, PendingOp::class], version = 1, exportSchema = false)
abstract class WrenDatabase : RoomDatabase() {
    abstract fun inboxDao(): InboxDao
    abstract fun pendingOpDao(): PendingOpDao

    companion object {
        fun create(context: Context): WrenDatabase {
            return Room.databaseBuilder(context, WrenDatabase::class.java, "wren.db").build()
        }
    }
}
