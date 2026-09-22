package dev.gpicalter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.gpicalter.data.dao.AlbumDao
import dev.gpicalter.data.dao.AssetDao
import dev.gpicalter.data.dao.AssetFileDao
import dev.gpicalter.data.dao.AuthTokenDao
import dev.gpicalter.data.dao.FingerprintDao
import dev.gpicalter.data.dao.ImportDao
import dev.gpicalter.data.dao.JobDao
import dev.gpicalter.data.dao.ThumbnailDao
import dev.gpicalter.data.dao.VolumeDao
import dev.gpicalter.data.entity.AlbumAssetEntity
import dev.gpicalter.data.entity.AlbumEntity
import dev.gpicalter.data.entity.AssetEntity
import dev.gpicalter.data.entity.AssetFileEntity
import dev.gpicalter.data.entity.AuthTokenEntity
import dev.gpicalter.data.entity.ImportItemEntity
import dev.gpicalter.data.entity.ImportSessionEntity
import dev.gpicalter.data.entity.JobEntity
import dev.gpicalter.data.entity.SourceFingerprintEntity
import dev.gpicalter.data.entity.ThumbnailEntity
import dev.gpicalter.data.entity.VolumeEntity

/**
 * The index.
 *
 * Lives in app-private **internal** storage, never on the USB drive. SQLite needs real POSIX
 * locking and it writes WAL and shared-memory sidecar files next to the database; SAF provides
 * neither, and exFAT does not honour fsync. A database on the drive would corrupt, not merely
 * run slowly.
 *
 * The drive holds bytes; this holds truth *about* those bytes -- and because identity is the
 * content hash, this file is rebuildable by rescanning if it is ever lost.
 */
@Database(
    version = 4,
    exportSchema = true,
    entities = [
        AssetEntity::class,
        AssetFileEntity::class,
        SourceFingerprintEntity::class,
        VolumeEntity::class,
        ThumbnailEntity::class,
        AlbumEntity::class,
        AlbumAssetEntity::class,
        JobEntity::class,
        ImportSessionEntity::class,
        ImportItemEntity::class,
        AuthTokenEntity::class,
    ],
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun assets(): AssetDao
    abstract fun assetFiles(): AssetFileDao
    abstract fun fingerprints(): FingerprintDao
    abstract fun volumes(): VolumeDao
    abstract fun thumbnails(): ThumbnailDao
    abstract fun albums(): AlbumDao
    abstract fun jobs(): JobDao
    abstract fun imports(): ImportDao
    abstract fun tokens(): AuthTokenDao

    companion object {
        private const val NAME = "gpic.db"

        /**
         * FILENAME and MTIME swapped places in [dev.gpicalter.data.entity.CaptureSource], so stored
         * values have to move with them. Done in three steps through a scratch value, because a
         * naive pair of updates would convert 3 to 4 and then straight back again.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE assets SET captured_at_source = 99 WHERE captured_at_source = 3")
                db.execSQL("UPDATE assets SET captured_at_source = 3 WHERE captured_at_source = 4")
                db.execSQL("UPDATE assets SET captured_at_source = 4 WHERE captured_at_source = 99")
            }
        }

        /** Adds where an asset came from. Nullable, so existing rows simply have no answer. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assets ADD COLUMN source_album TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_source_album ON assets (source_album)")
            }
        }

        /**
         * Index only. Cache eviction asks "does another asset share this thumbnail file?" once per
         * candidate, and on a library with tens of thousands of rows that question has to be
         * answerable without scanning the table each time.
         *
         * Both columns, because a `cache_rel_path` index alone does not get used: the query also
         * filters on `state`, and with no ANALYZE statistics the planner picks the `state` index --
         * which matches nearly every row -- leaving the new index idle. Indexing the pair makes it
         * the best plan unconditionally.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_thumbnails_cache_rel_path_state " +
                        "ON thumbnails (cache_rel_path, state)",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) { instance ?: build(context).also { instance = it } }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                // WAL: readers never block the writer, which matters because the HTTP server
                // reads the timeline while the job runner is writing thumbnail rows.
                // (Room already enables PRAGMA foreign_keys itself, so cascades work as declared.)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // Deliberately NO fallbackToDestructiveMigration. Losing this database means
                // re-hashing and re-thumbnailing the entire library -- hours of work on a phone.
                // Every schema change gets a hand-written Migration and a test against the
                // exported schema JSON.
                .build()

        /** The on-disk file, so it can be exported to the drive before a risky migration. */
        fun file(context: Context) = context.getDatabasePath(NAME)
    }
}
