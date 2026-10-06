package io.github.wilsonnet.droidsentenceminer.dict

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "dictionaries")
data class DictionaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val revision: String,
    val format: Int = 3,
    val version: Int? = null,
    val author: String? = null,
    val description: String? = null,
    val attribution: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val sequenced: Boolean? = null,
    val frequencyMode: String? = null,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val styles: String? = null,
    val zipPath: String? = null,
    val termCount: Long = 0,
    val kanjiCount: Long = 0,
)

@Entity(
    tableName = "terms",
    indices = [
        Index("expression"),
        Index("reading"),
        Index("dictId"),
        Index("sequence"),
    ],
)
data class TermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dictId: Long,
    val expression: String,
    val reading: String,
    val definitionTags: String,
    val rules: String,
    val score: Int,
    val glossaryJson: String,
    val sequence: Long,
    val termTags: String,
)

@Entity(
    tableName = "kanji",
    indices = [Index("character"), Index("dictId")],
)
data class KanjiEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dictId: Long,
    val character: String,
    val onyomi: String,
    val kunyomi: String,
    val tags: String,
    val meaningsJson: String,
    val statsJson: String,
)

@Entity(
    tableName = "tags",
    indices = [Index("dictId")],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dictId: Long,
    val name: String,
    val category: String,
    val order: Int,
    val notes: String,
    val score: Int,
)

@Entity(
    tableName = "term_meta",
    indices = [Index("expression"), Index("dictId")],
)
data class TermMetaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dictId: Long,
    val expression: String,
    val mode: String,
    val dataJson: String,
)

@Entity(
    tableName = "kanji_meta",
    indices = [Index("character"), Index("dictId")],
)
data class KanjiMetaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dictId: Long,
    val character: String,
    val mode: String,
    val dataJson: String,
)

/** A card the user mined from OCR or the popup lookup. */
@Entity(tableName = "mined_cards")
data class MinedCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val reading: String,
    val sentence: String,
    val meaningSnippet: String,
    val dictionaryTitle: String?,
    val imagePath: String?,
    val timestamp: Long,
    val ankiDeck: String?,
    val ankiModel: String?,
    val addedToAnki: Boolean = false,
)

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dictionaries ORDER BY priority ASC, id ASC")
    fun observeAll(): Flow<List<DictionaryEntity>>

    @Query("SELECT * FROM dictionaries ORDER BY priority ASC, id ASC")
    suspend fun getAll(): List<DictionaryEntity>

    @Query("SELECT * FROM dictionaries WHERE enabled = 1 ORDER BY priority ASC, id ASC")
    suspend fun getEnabled(): List<DictionaryEntity>

    @Query("SELECT * FROM dictionaries WHERE LOWER(title) = LOWER(:title) LIMIT 1")
    suspend fun findByTitle(title: String): DictionaryEntity?

    @Query("SELECT MAX(priority) FROM dictionaries")
    suspend fun maxPriority(): Int?

    @Query("SELECT COUNT(*) FROM dictionaries")
    suspend fun count(): Int

    @Insert
    suspend fun insert(dictionary: DictionaryEntity): Long

    @Query("UPDATE dictionaries SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE dictionaries SET termCount = :terms, kanjiCount = :kanji WHERE id = :id")
    suspend fun updateCounts(id: Long, terms: Long, kanji: Long)

    @Query("DELETE FROM dictionaries WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TermDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(terms: List<TermEntity>)

    @Query("DELETE FROM terms WHERE dictId = :dictId")
    suspend fun deleteByDict(dictId: Long)

    @Query("SELECT COUNT(*) FROM terms WHERE dictId = :dictId")
    suspend fun countByDict(dictId: Long): Long

    @Query(
        """
        SELECT t.* FROM terms t
        INNER JOIN dictionaries d ON d.id = t.dictId
        WHERE d.enabled = 1 AND (t.expression IN (:forms) OR t.reading IN (:forms))
        """,
    )
    suspend fun findByForms(forms: List<String>): List<TermEntity>

    @Query(
        """
        SELECT t.* FROM terms t
        INNER JOIN dictionaries d ON d.id = t.dictId
        WHERE d.enabled = 1 AND t.sequence = :sequence
        ORDER BY t.score DESC
        """,
    )
    suspend fun findBySequence(sequence: Long): List<TermEntity>

    @Query("SELECT * FROM terms WHERE dictId = :dictId ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun pageByDict(dictId: Long, limit: Int, offset: Int): List<TermEntity>
}

@Dao
interface KanjiDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(kanji: List<KanjiEntity>)

    @Query("DELETE FROM kanji WHERE dictId = :dictId")
    suspend fun deleteByDict(dictId: Long)

    @Query("SELECT COUNT(*) FROM kanji WHERE dictId = :dictId")
    suspend fun countByDict(dictId: Long): Long

    @Query(
        """
        SELECT k.* FROM kanji k
        INNER JOIN dictionaries d ON d.id = k.dictId
        WHERE d.enabled = 1 AND k.character IN (:characters)
        """,
    )
    suspend fun findByCharacters(characters: List<String>): List<KanjiEntity>
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<TagEntity>)

    @Query("DELETE FROM tags WHERE dictId = :dictId")
    suspend fun deleteByDict(dictId: Long)

    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>
}

@Dao
interface MetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTermMeta(items: List<TermMetaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKanjiMeta(items: List<KanjiMetaEntity>)

    @Query("DELETE FROM term_meta WHERE dictId = :dictId")
    suspend fun deleteTermMetaByDict(dictId: Long)

    @Query("DELETE FROM kanji_meta WHERE dictId = :dictId")
    suspend fun deleteKanjiMetaByDict(dictId: Long)

    @Query(
        """
        SELECT m.* FROM term_meta m
        INNER JOIN dictionaries d ON d.id = m.dictId
        WHERE d.enabled = 1 AND m.expression IN (:forms)
        """,
    )
    suspend fun findTermMeta(forms: List<String>): List<TermMetaEntity>
}

@Dao
interface MinedCardDao {
    @Insert
    suspend fun insert(card: MinedCardEntity): Long

    @Query("SELECT * FROM mined_cards ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<MinedCardEntity>>

    @Query("UPDATE mined_cards SET addedToAnki = :added WHERE id = :id")
    suspend fun setAddedToAnki(id: Long, added: Boolean)

    @Query("DELETE FROM mined_cards WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [
        DictionaryEntity::class,
        TermEntity::class,
        KanjiEntity::class,
        TagEntity::class,
        TermMetaEntity::class,
        KanjiMetaEntity::class,
        MinedCardEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class DictDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao
    abstract fun termDao(): TermDao
    abstract fun kanjiDao(): KanjiDao
    abstract fun tagDao(): TagDao
    abstract fun metaDao(): MetaDao
    abstract fun minedCardDao(): MinedCardDao

    companion object {
        fun build(context: Context): DictDatabase = Room.databaseBuilder(
            context.applicationContext,
            DictDatabase::class.java,
            "dict.db",
        )
            .fallbackToDestructiveMigration()
            .build()
    }
}
