package com.jesus.dronplataneras.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprueba en un dispositivo que la migración 1 -> 2 conserva los lotes y misiones existentes,
 * les asigna un UUID único y deja el esquema idéntico al que Room espera.
 * Ejecutar con: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AgroMigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgroDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2KeepsDataAndAddsUuids() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO lotes (id, nombre, areaEstimadaHa, fechaCreacion) VALUES (1, 'Lote A', 1.5, 1000), (2, 'Lote B', 2.0, 2000)")
            execSQL("INSERT INTO parametros_vuelo (id, alturaMetros, traslapePorcentaje, margenInternoMetros) VALUES (1, 60.0, 75.0, 5.0)")
            execSQL(
                "INSERT INTO misiones (id, nombre, loteId, parametrosId, estado, duracionEstimadaMin, fotosEstimadas, " +
                    "rutaWaypointsJson, fechaCreacion, fechaActualizacion) VALUES (1, 'Misión 1', 1, 1, 'SINCRONIZADO', 12.0, 300, NULL, 1000, 1000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        db.query("SELECT uuid FROM lotes ORDER BY id").use { c ->
            val uuids = buildList { while (c.moveToNext()) add(c.getString(0)) }
            assertEquals(2, uuids.size)
            assertEquals("los UUID deben ser distintos", 2, uuids.toSet().size)
            uuids.forEach { assertTrue("UUID mal formado: $it", Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(it)) }
        }
        db.query("SELECT nombre, uuid FROM misiones WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Misión 1", c.getString(0))
            assertTrue(c.getString(1).isNotBlank())
        }
        db.query("SELECT alturaCopaMetros, traslapeFrontalPorcentaje, modoCaptura FROM parametros_vuelo WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4.0, c.getDouble(0), 0.0)
            assertEquals(80.0, c.getDouble(1), 0.0)
            assertEquals("VIDEO", c.getString(2))
        }
        // Las tablas nuevas existen y aceptan datos
        db.execSQL(
            "INSERT INTO vuelos (uuid, misionId, estado, fechaInicio, modoCaptura, perimetroJson, rutaJson, totalWaypoints, siguienteWaypoint) " +
                "VALUES ('v-1', 1, 'EN_CURSO', 1, 'VIDEO', '[]', '{}', 10, 0)"
        )
        db.execSQL(
            "INSERT INTO salidas (uuid, vueloId, indice, desdeWaypoint, hastaWaypoint, estado, inicioMs, bateriaInicio, distanciaM) " +
                "VALUES ('s-1', 1, 0, 0, 9, 'EN_CURSO', 1, 100, 0.0)"
        )
    }
}
