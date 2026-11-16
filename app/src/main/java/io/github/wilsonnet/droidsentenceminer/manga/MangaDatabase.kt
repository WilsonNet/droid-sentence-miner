package io.github.wilsonnet.droidsentenceminer.manga

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
import kotlinx.coroutines.flow.Flow

/** One imported `.cbz` manga volume in the local library. */
@Entity(tableName = "manga_volumes")
data class MangaVolumeEntity(
    /** Auto-generated volume ID. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Volume display title. */
    val title: String,
    /** Optional series title (from `.mokuro`). */
    val seriesTitle: String = "",
    /** Local path to the stored `.cbz` archive. */
    val cbzPath: String,
    /** Optional path to the cached cover JPEG thumbnail. */
    val coverPath: String? = null,
    /** Total image pages inside the archive. */
    val pageCount: Int,
    /** Zero-based last-read page index. */
    val currentPage: Int = 0,
    /** True when `.mokuro` data has been imported for this volume. */
    val hasMokuro: Boolean = false,
    /** Total recognized characters from `.mokuro` (0 for raw volumes). */
    val totalChars: Int = 0,
    /** Epoch ms when the volume was imported. */
    val addedAt: Long,
    /** Epoch ms when the volume was last opened or turned. */
    val lastReadAt: Long,
    /** Sorted entry names inside [cbzPath] stored as JSON array so opening avoids re-scanning the zip. */
    val entriesJson: String,
)

/** Cached OCR / `.mokuro` blocks for a single page of a volume. */
@Entity(tableName = "manga_page_ocr", primaryKeys = ["volumeId", "pageIndex"])
data class MangaPageOcrEntity(
    /** Parent volume ID. */
    val volumeId: Long,
    /** Zero-based page index inside the volume. */
    val pageIndex: Int,
    /** Image width in pixels matching block coordinates. */
    val imgWidth: Int,
    /** Image height in pixels matching block coordinates. */
    val imgHeight: Int,
    /** JSON-serialized list of [MangaBlock] items. */
    val blocksJson: String,
    /** Origin identifier (`mokuro`, `ocr`, or `edited`). */
    val source: String,
    /** Epoch ms when this cache row was written. */
    val updatedAt: Long,
)

/** Room DAO for volume metadata, reading progress, and deletion. */
@Dao
interface MangaVolumeDao {
    /** Emits all volumes ordered by most recently read. */
    @Query("SELECT * FROM manga_volumes ORDER BY lastReadAt DESC, addedAt DESC")
    fun observeVolumes(): Flow<List<MangaVolumeEntity>>

    /** Fetches a single volume by [id], or null if missing. */
    @Query("SELECT * FROM manga_volumes WHERE id = :id")
    suspend fun getVolume(id: Long): MangaVolumeEntity?

    /** Inserts a new volume row and returns its generated ID. */
    @Insert
    suspend fun insertVolume(volume: MangaVolumeEntity): Long

    /** Persists reading progress ([page]) and last-read timestamp. */
    @Query("UPDATE manga_volumes SET currentPage = :page, lastReadAt = :readAt WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int, readAt: Long)

    /** Records `.mokuro` metadata after attaching or importing an OCR sidecar. */
    @Query("UPDATE manga_volumes SET hasMokuro = :hasMokuro, totalChars = :totalChars WHERE id = :id")
    suspend fun updateMokuroMeta(id: Long, hasMokuro: Boolean, totalChars: Int)

    /** Deletes the volume row with [id]. */
    @Query("DELETE FROM manga_volumes WHERE id = :id")
    suspend fun deleteVolume(id: Long)
}

/** Room DAO for cached per-page `.mokuro` and on-device OCR blocks. */
@Dao
interface MangaPageOcrDao {
    /** Fetches cached OCR blocks for [volumeId] page [pageIndex], or null if not yet run. */
    @Query("SELECT * FROM manga_page_ocr WHERE volumeId = :volumeId AND pageIndex = :pageIndex")
    suspend fun getPageOcr(volumeId: Long, pageIndex: Int): MangaPageOcrEntity?

    /** Inserts or replaces OCR blocks for one page. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPageOcr(page: MangaPageOcrEntity)

    /** Inserts or replaces OCR blocks for multiple pages in one transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPagesOcr(pages: List<MangaPageOcrEntity>)

    /** Removes cached OCR rows for all pages of [volumeId]. */
    @Query("DELETE FROM manga_page_ocr WHERE volumeId = :volumeId")
    suspend fun deleteVolumePages(volumeId: Long)
}

/** Kept separate from `dict.db` so dictionary destructive migrations never touch the manga library. */
@Database(entities = [MangaVolumeEntity::class, MangaPageOcrEntity::class], version = 1, exportSchema = false)
abstract class MangaDatabase : RoomDatabase() {
    /** Returns the volume DAO. */
    abstract fun volumeDao(): MangaVolumeDao

    /** Returns the per-page OCR cache DAO. */
    abstract fun pageOcrDao(): MangaPageOcrDao

    /** Factory helpers for [MangaDatabase]. */
    companion object {
        /** Opens or creates the on-disk `manga.db` Room database. */
        fun build(context: Context): MangaDatabase {
            val builder =
                Room.databaseBuilder(
                    context.applicationContext,
                    MangaDatabase::class.java,
                    "manga.db",
                )
            return builder.build()
        }
    }
}
