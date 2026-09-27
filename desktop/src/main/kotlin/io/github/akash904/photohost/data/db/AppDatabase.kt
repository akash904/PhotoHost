package io.github.akash904.photohost.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.akash904.photohost.data.dao.AlbumDao
import io.github.akash904.photohost.data.dao.AssetDao
import io.github.akash904.photohost.data.dao.AssetFileDao
import io.github.akash904.photohost.data.dao.AuthTokenDao
import io.github.akash904.photohost.data.dao.FingerprintDao
import io.github.akash904.photohost.data.dao.ImportDao
import io.github.akash904.photohost.data.dao.JobDao
import io.github.akash904.photohost.data.dao.ThumbnailDao
import io.github.akash904.photohost.data.dao.VolumeDao
import io.github.akash904.photohost.data.entity.AlbumAssetEntity
import io.github.akash904.photohost.data.entity.AlbumEntity
import io.github.akash904.photohost.data.entity.AssetEntity
import io.github.akash904.photohost.data.entity.AssetFileEntity
import io.github.akash904.photohost.data.entity.AuthTokenEntity
import io.github.akash904.photohost.data.entity.ImportItemEntity
import io.github.akash904.photohost.data.entity.ImportSessionEntity
import io.github.akash904.photohost.data.entity.JobEntity
import io.github.akash904.photohost.data.entity.SourceFingerprintEntity
import io.github.akash904.photohost.data.entity.ThumbnailEntity
import io.github.akash904.photohost.data.entity.VolumeEntity
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * The index.
 *
 * The same @Database as the phone app's -- same version, same entities, same DAOs -- so Room exports
 * the same schema JSON with the same identity hash. That equality is checked, not assumed: compare
 * `schemas/.../4.json` here against `android/app/schemas/.../4.json`.
 *
 * DIVERGES FROM the phone app only in how it is opened: Room's JVM builder with SQLite compiled in
 * (BundledSQLiteDriver) instead of Android's framework SQLite, and migrations written against
 * SQLiteConnection, the only migration API Room offers off Android. The SQL in them is the phone's,
 * character for character.
 *
 * Lives in the data directory, never under the library folder -- the same rule as the phone, and on
 * a PC the library may be on a USB disk or a network share, where SQLite's locking and WAL files are
 * not safe.
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
        const val NAME = "gpic.db"

        /**
         * FILENAME and MTIME swapped places in [io.github.akash904.photohost.data.entity.CaptureSource], so stored
         * values have to move with them. Done in three steps through a scratch value, because a
         * naive pair of updates would convert 3 to 4 and then straight back again.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("UPDATE assets SET captured_at_source = 99 WHERE captured_at_source = 3")
                connection.execSQL("UPDATE assets SET captured_at_source = 3 WHERE captured_at_source = 4")
                connection.execSQL("UPDATE assets SET captured_at_source = 4 WHERE captured_at_source = 99")
            }
        }

        /** Adds where an asset came from. Nullable, so existing rows simply have no answer. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE assets ADD COLUMN source_album TEXT")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_assets_source_album ON assets (source_album)")
            }
        }

        /** Index only; see the phone app's AppDatabase for why it covers both columns. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_thumbnails_cache_rel_path_state " +
                        "ON thumbnails (cache_rel_path, state)",
                )
            }
        }

        /** Exposed so migration tests run exactly the chain production does. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun open(file: File): AppDatabase {
            file.parentFile?.mkdirs()
            return Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(*MIGRATIONS)
                // WAL: readers never block the writer, which matters because the HTTP server reads
                // the timeline while the job runner is writing thumbnail rows.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // Deliberately NO fallbackToDestructiveMigration. Losing this database means
                // re-hashing and re-thumbnailing the entire library.
                .build()
        }
    }
}
