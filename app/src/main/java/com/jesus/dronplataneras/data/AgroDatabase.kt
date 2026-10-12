package com.jesus.dronplataneras.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base de datos local de AgroScan (`agroscan.db`): lotes, vértices, misiones, parámetros de vuelo y el historial de vuelos y salidas.
 * El esquema se exporta a `app/schemas` y los cambios se migran en [MIGRATION_1_2] y siguientes (nunca se borra la base).
 */
@Database(
    entities = [
        LoteEntity::class, VerticeEntity::class, ParametrosVueloEntity::class, MisionEntity::class,
        VueloEntity::class, SalidaEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AgroDatabase : RoomDatabase() {
    abstract fun loteDao(): LoteDao
    abstract fun misionDao(): MisionDao
    abstract fun vueloDao(): VueloDao

    companion object {
        @Volatile private var instance: AgroDatabase? = null

        fun get(context: Context): AgroDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AgroDatabase::class.java, "agroscan.db")
                    // Cuando cambie el esquema: subir version y agregar una Migration; nunca
                    // fallbackToDestructiveMigration, que borraría lotes levantados en campo.
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
