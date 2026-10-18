package com.jesus.dronplataneras.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** UUID v4 en SQL puro; cada llamada a randomblob() se evalúa por fila. */
private const val SQL_UUID =
    "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || " +
        "substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))"

/**
 * v1 -> v2:
 *  - uuid en lotes y misiones (con relleno de los registros existentes) para sincronizar con el escritorio;
 *  - parámetros nuevos de la misión: altura de la copa, traslape frontal y modo de captura (video);
 *  - historial de ejecuciones: tablas `vuelos` y `salidas`.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE lotes ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE lotes SET uuid = $SQL_UUID")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_lotes_uuid ON lotes (uuid)")

        db.execSQL("ALTER TABLE misiones ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE misiones SET uuid = $SQL_UUID")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_misiones_uuid ON misiones (uuid)")

        db.execSQL("ALTER TABLE parametros_vuelo ADD COLUMN alturaCopaMetros REAL NOT NULL DEFAULT 4.0")
        db.execSQL("ALTER TABLE parametros_vuelo ADD COLUMN traslapeFrontalPorcentaje REAL NOT NULL DEFAULT 80.0")
        db.execSQL("ALTER TABLE parametros_vuelo ADD COLUMN modoCaptura TEXT NOT NULL DEFAULT 'VIDEO'")

        // Idéntico al SQL que Room exporta en schemas/.../2.json (si no coincide, Room rechaza la migración)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `vuelos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, " +
                "`misionId` INTEGER NOT NULL, `estado` TEXT NOT NULL, `fechaInicio` INTEGER NOT NULL, `fechaFin` INTEGER, " +
                "`modoCaptura` TEXT NOT NULL, `perimetroJson` TEXT NOT NULL, `rutaJson` TEXT NOT NULL, " +
                "`totalWaypoints` INTEGER NOT NULL, `siguienteWaypoint` INTEGER NOT NULL, " +
                "FOREIGN KEY(`misionId`) REFERENCES `misiones`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vuelos_misionId` ON `vuelos` (`misionId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vuelos_uuid` ON `vuelos` (`uuid`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `salidas` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL, " +
                "`vueloId` INTEGER NOT NULL, `indice` INTEGER NOT NULL, `desdeWaypoint` INTEGER NOT NULL, " +
                "`hastaWaypoint` INTEGER NOT NULL, `estado` TEXT NOT NULL, `motivo` TEXT, `inicioMs` INTEGER NOT NULL, " +
                "`finMs` INTEGER, `bateriaInicio` INTEGER NOT NULL, `bateriaFin` INTEGER, `videoInicioMs` INTEGER, " +
                "`videoFinMs` INTEGER, `distanciaM` REAL NOT NULL, `logPath` TEXT, " +
                "FOREIGN KEY(`vueloId`) REFERENCES `vuelos`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_salidas_vueloId_indice` ON `salidas` (`vueloId`, `indice`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_salidas_uuid` ON `salidas` (`uuid`)")
    }
}
