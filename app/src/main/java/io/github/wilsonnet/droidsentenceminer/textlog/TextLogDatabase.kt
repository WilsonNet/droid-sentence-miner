package io.github.wilsonnet.droidsentenceminer.textlog

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One recognized line of game/video text — the texthooker log entry GSM keeps for every
 * line it sees. [startMs]/[endMs] are wall-clock times used to cut the voice clip from
 * the replay buffer.
 */
@Entity(tableName = "text_lines", indices = [Index("startMs")])
data class TextLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val startMs: Long,
    /** When the text disappeared or was replaced; null while still on screen. */
    val endMs: Long? = null,
    val source: String = SOURCE_AUTO,
    val screenshotPath: String? = null,
    val minedCount: Int = 0,
) {
    companion object {
        const val SOURCE_AUTO = "auto"
        const val SOURCE_MANUAL = "manual"
    }
}

@Dao
interface TextLineDao {
    @Query("SELECT * FROM text_lines ORDER BY startMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 2000): Flow<List<TextLineEntity>>

    @Query("SELECT * FROM text_lines ORDER BY startMs DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TextLineEntity>

    @Query("SELECT * FROM text_lines WHERE id = :id")
    suspend fun get(id: Long): TextLineEntity?

    @Query("SELECT * FROM text_lines WHERE startMs > :startMs OR (startMs = :startMs AND id > :id) ORDER BY startMs ASC, id ASC LIMIT 1")
    suspend fun next(startMs: Long, id: Long): TextLineEntity?

    @Insert
    suspend fun insert(line: TextLineEntity): Long

    @Query("UPDATE text_lines SET endMs = :endMs WHERE id = :id")
    suspend fun setEnd(id: Long, endMs: Long)

    @Query("UPDATE text_lines SET text = :text WHERE id = :id")
    suspend fun setText(id: Long, text: String)

    @Query("UPDATE text_lines SET screenshotPath = :path WHERE id = :id")
    suspend fun setScreenshot(id: Long, path: String?)

    @Query("UPDATE text_lines SET minedCount = minedCount + 1 WHERE id = :id")
    suspend fun markMined(id: Long)

    @Query("SELECT screenshotPath FROM text_lines WHERE screenshotPath IS NOT NULL ORDER BY startMs DESC LIMIT -1 OFFSET :keep")
    suspend fun screenshotsBeyond(keep: Int): List<String>

    @Query("UPDATE text_lines SET screenshotPath = NULL WHERE screenshotPath IN (:paths)")
    suspend fun clearScreenshots(paths: List<String>)

    @Query("SELECT screenshotPath FROM text_lines WHERE screenshotPath IS NOT NULL")
    suspend fun allScreenshots(): List<String>

    @Query("DELETE FROM text_lines WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM text_lines")
    suspend fun clear()
}

/** Kept apart from the dictionary DB so its schema can evolve without touching dictionaries. */
@Database(entities = [TextLineEntity::class], version = 1, exportSchema = false)
abstract class TextLogDatabase : RoomDatabase() {
    abstract fun lines(): TextLineDao

    companion object {
        fun build(context: Context): TextLogDatabase = Room.databaseBuilder(
            context.applicationContext,
            TextLogDatabase::class.java,
            "textlog.db",
        ).build()
    }
}
