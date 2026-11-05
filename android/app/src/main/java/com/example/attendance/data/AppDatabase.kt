package com.example.attendance.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Employee::class, Fingerprint::class, Punch::class, Department::class],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun employees(): EmployeeDao
    abstract fun fingerprints(): FingerprintDao
    abstract fun punches(): PunchDao
    abstract fun departments(): DepartmentDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 held one template per person on `employees`; v2 moves templates to
         * `fingerprints` so one person can enrol several fingers.
         *
         * The existing template is carried across as finger 1 - nobody has to
         * re-enrol. `employees` is rebuilt rather than altered because dropping
         * a column needs SQLite 3.35, which is newer than this device's Android
         * 10 ships with.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fingerprints` (
                        `employeePin` TEXT NOT NULL,
                        `fingerIndex` INTEGER NOT NULL,
                        `template` BLOB NOT NULL,
                        `enrolledAt` INTEGER NOT NULL,
                        `pendingUpload` INTEGER NOT NULL,
                        PRIMARY KEY(`employeePin`, `fingerIndex`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fingerprints_employeePin` ON `fingerprints` (`employeePin`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fingerprints_pendingUpload` ON `fingerprints` (`pendingUpload`)")

                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `fingerprints`
                        (`employeePin`, `fingerIndex`, `template`, `enrolledAt`, `pendingUpload`)
                    SELECT `pin`, 1, `template`, `enrolledAt`, `pendingUpload` FROM `employees`
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `employees_new` (
                        `pin` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `enrolledAt` INTEGER NOT NULL,
                        `pendingUpload` INTEGER NOT NULL,
                        PRIMARY KEY(`pin`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO `employees_new` (`pin`, `name`, `enrolledAt`, `pendingUpload`) " +
                        "SELECT `pin`, `name`, `enrolledAt`, `pendingUpload` FROM `employees`"
                )
                db.execSQL("DROP TABLE `employees`")
                db.execSQL("ALTER TABLE `employees_new` RENAME TO `employees`")
            }
        }

        /**
         * v3 adds department/gender to staff and the recording site to each
         * punch. Plain ALTER TABLE ADD COLUMN, so existing rows and any punch
         * still waiting to upload are untouched.
         *
         * The defaults must match the entities' `@ColumnInfo(defaultValue=...)`
         * exactly - Room validates declared defaults, not only types, and a
         * mismatch fails at startup with an unhelpful "migration didn't
         * properly handle" error.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `employees` ADD COLUMN `department` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `employees` ADD COLUMN `gender` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `employees` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `punches` ADD COLUMN `location` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v4 splits the name, adds tombstones and per-row sync errors, and
         * introduces the departments table.
         *
         * `employees` and `fingerprints` are both rebuilt rather than altered:
         * `firstName`/`lastName` have to be derived from the old `name` as the
         * rows are copied, and SQLite cannot add a foreign key to an existing
         * table at all. Dropping a column would need SQLite 3.35, newer than
         * this device's Android 10 ships with, so rebuild-and-copy is the only
         * route regardless.
         *
         * Order matters. `employees` is rebuilt first, while the old
         * `fingerprints` still has no foreign key and so does not object to the
         * table underneath it being replaced; the new `fingerprints`, which does
         * have one, is created afterwards against the table that will survive.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room runs migrations inside a transaction, and `PRAGMA
                // foreign_keys` is a no-op there. defer_foreign_keys is the one
                // that works: constraints are checked at COMMIT instead of per
                // statement, so the tables can be out of step mid-migration.
                db.execSQL("PRAGMA defer_foreign_keys = TRUE")

                // ---------------------------------------------------- employees
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `employees_new` (
                        `pin` TEXT NOT NULL,
                        `firstName` TEXT NOT NULL,
                        `lastName` TEXT NOT NULL,
                        `department` TEXT NOT NULL DEFAULT '',
                        `gender` TEXT NOT NULL DEFAULT '',
                        `enrolledAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        `deletedAt` INTEGER,
                        `pendingUpload` INTEGER NOT NULL,
                        `lastError` TEXT,
                        PRIMARY KEY(`pin`)
                    )
                    """.trimIndent()
                )

                // Split on the FIRST space: everything before it is the given
                // name, the remainder is the surname. That is right for "Ada
                // Obi" and for "Ada Grace Obi", and wrong for anyone entered
                // surname-first - which is common enough here that the staff
                // list is the place to fix it, by editing the person. A name
                // with no space at all keeps the whole string as the first
                // name, because the punch messages greet people by first name
                // and an empty greeting is worse than an imperfect surname.
                db.execSQL(
                    """
                    INSERT INTO `employees_new`
                        (`pin`, `firstName`, `lastName`, `department`, `gender`,
                         `enrolledAt`, `updatedAt`, `deletedAt`, `pendingUpload`, `lastError`)
                    SELECT
                        `pin`,
                        CASE WHEN instr(`name`, ' ') > 0
                             THEN substr(`name`, 1, instr(`name`, ' ') - 1)
                             ELSE `name` END,
                        CASE WHEN instr(`name`, ' ') > 0
                             THEN substr(`name`, instr(`name`, ' ') + 1)
                             ELSE '' END,
                        `department`, `gender`, `enrolledAt`, `updatedAt`,
                        NULL, `pendingUpload`, NULL
                    FROM `employees`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `employees`")
                db.execSQL("ALTER TABLE `employees_new` RENAME TO `employees`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_employees_pendingUpload` ON `employees` (`pendingUpload`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_employees_lastName` ON `employees` (`lastName`)")

                // ------------------------------------------------- fingerprints
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fingerprints_new` (
                        `employeePin` TEXT NOT NULL,
                        `fingerIndex` INTEGER NOT NULL,
                        `template` BLOB NOT NULL,
                        `enrolledAt` INTEGER NOT NULL,
                        `pendingUpload` INTEGER NOT NULL,
                        `lastError` TEXT,
                        PRIMARY KEY(`employeePin`, `fingerIndex`),
                        FOREIGN KEY(`employeePin`) REFERENCES `employees`(`pin`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                // Adopt orphaned templates instead of discarding them.
                //
                // A template whose employee row went missing would violate the
                // new foreign key, and a failed migration on a terminal is a
                // crash loop at startup that no admin on site can clear. The
                // obvious fix - skip orphans on the way across - is quietly
                // destructive: fingerprint templates are special-category
                // personal data that cost a physical enrolment to collect, and
                // there is no backup anywhere (allowBackup=false, by design).
                // A migration that silently deletes them because a JOIN came up
                // empty is the worst possible outcome, and it would look like a
                // clean success.
                //
                // So synthesise the missing person instead. They come back as
                // their own SF ID with no name, which is visible and fixable on
                // the staff list; the templates survive either way.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `employees`
                        (`pin`, `firstName`, `lastName`, `department`, `gender`,
                         `enrolledAt`, `updatedAt`, `deletedAt`, `pendingUpload`, `lastError`)
                    SELECT DISTINCT f.`employeePin`, f.`employeePin`, '', '', '',
                           COALESCE(f.`enrolledAt`, 0), COALESCE(f.`enrolledAt`, 0), NULL, 1, NULL
                    FROM `fingerprints` f
                    WHERE NOT EXISTS (SELECT 1 FROM `employees` e WHERE e.`pin` = f.`employeePin`)
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT INTO `fingerprints_new`
                        (`employeePin`, `fingerIndex`, `template`, `enrolledAt`, `pendingUpload`, `lastError`)
                    SELECT f.`employeePin`, f.`fingerIndex`, f.`template`, f.`enrolledAt`,
                           f.`pendingUpload`, NULL
                    FROM `fingerprints` f
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `fingerprints`")
                db.execSQL("ALTER TABLE `fingerprints_new` RENAME TO `fingerprints`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fingerprints_employeePin` ON `fingerprints` (`employeePin`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fingerprints_pendingUpload` ON `fingerprints` (`pendingUpload`)")

                // --------------------------------------------------- departments
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `departments` (
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        `deletedAt` INTEGER,
                        `pendingUpload` INTEGER NOT NULL,
                        `lastError` TEXT,
                        PRIMARY KEY(`name`)
                    )
                    """.trimIndent()
                )

                // Seed from what people are already filed under, so the first
                // enrolment after the update has a list to choose from instead
                // of an empty dropdown and a blocked admin. Marked pending so
                // they reach the server like any other local creation.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `departments`
                        (`name`, `createdAt`, `updatedAt`, `deletedAt`, `pendingUpload`, `lastError`)
                    SELECT DISTINCT `department`, `enrolledAt`, `enrolledAt`, NULL, 1, NULL
                    FROM `employees`
                    WHERE `department` IS NOT NULL AND trim(`department`) <> ''
                    """.trimIndent()
                )

                // -------------------------------------------------------- punches
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_punches_employeePin` ON `punches` (`employeePin`)")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "zk_attendance.db"
            )
                // No fallbackToDestructiveMigration. This database holds the
                // only copy of the fingerprint templates and of any punch that
                // has not reached Snowflake yet; wiping it on a schema bump
                // would mean re-enrolling every member of staff. Write real
                // migrations instead.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
