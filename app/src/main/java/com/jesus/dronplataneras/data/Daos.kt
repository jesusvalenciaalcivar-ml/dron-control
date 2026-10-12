package com.jesus.dronplataneras.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.jesus.dronplataneras.survey.GeoCoord
import kotlinx.coroutines.flow.Flow

/**
 * Acceso a `lotes` y `vertices`. El lote y su perímetro siempre se escriben juntos, en una transacción.
 */
@Dao
abstract class LoteDao {

    @Insert
    abstract suspend fun insertLote(lote: LoteEntity): Long

    @Update
    abstract suspend fun updateLote(lote: LoteEntity)

    @Insert
    abstract suspend fun insertVertices(vertices: List<VerticeEntity>)

    @Query("DELETE FROM vertices WHERE loteId = :loteId")
    abstract suspend fun deleteVertices(loteId: Long)

    /** Inserta el lote y su perímetro en una sola transacción: o se guarda todo o nada. */
    @Transaction
    open suspend fun insertLoteConVertices(lote: LoteEntity, puntos: List<GeoCoord>): Long {
        val id = insertLote(lote)
        insertVertices(puntos.toVertices(id))
        return id
    }

    /** Reemplaza el perímetro (edición) conservando el id del lote y sus misiones. */
    @Transaction
    open suspend fun updateLoteConVertices(lote: LoteEntity, puntos: List<GeoCoord>) {
        updateLote(lote)
        deleteVertices(lote.id)
        insertVertices(puntos.toVertices(lote.id))
    }

    @Transaction
    @Query("SELECT * FROM lotes ORDER BY fechaCreacion DESC")
    abstract fun observeLotes(): Flow<List<LoteConVertices>>

    @Transaction
    @Query("SELECT * FROM lotes WHERE id = :id")
    abstract suspend fun getLote(id: Long): LoteConVertices?

    private fun List<GeoCoord>.toVertices(loteId: Long) = mapIndexed { i, p ->
        VerticeEntity(loteId = loteId, ordenSecuencia = i, latitud = p.latitude, longitud = p.longitude)
    }
}

/**
 * Acceso a `misiones` y `parametros_vuelo`, que se crean, actualizan y borran juntos.
 */
@Dao
abstract class MisionDao {

    @Insert
    abstract suspend fun insertParametros(p: ParametrosVueloEntity): Long

    @Update
    abstract suspend fun updateParametros(p: ParametrosVueloEntity)

    @Insert
    abstract suspend fun insertMision(m: MisionEntity): Long

    @Update
    abstract suspend fun updateMision(m: MisionEntity)

    @Query("DELETE FROM parametros_vuelo WHERE id = :id")
    abstract suspend fun deleteParametros(id: Long)

    /** Crea parámetros + misión de forma atómica. Devuelve el id de la misión. */
    @Transaction
    open suspend fun crearMision(parametros: ParametrosVueloEntity, mision: MisionEntity): Long {
        val pid = insertParametros(parametros.copy(id = 0))
        return insertMision(mision.copy(id = 0, parametrosId = pid))
    }

    /** Actualiza parámetros y misión juntos (al volver a editar la planificación). */
    @Transaction
    open suspend fun actualizarMision(parametros: ParametrosVueloEntity, mision: MisionEntity) {
        updateParametros(parametros)
        updateMision(mision.copy(parametrosId = parametros.id, fechaActualizacion = System.currentTimeMillis()))
    }

    /** Borra la misión y sus parámetros (la FK en cascada elimina la misión al borrar los parámetros). */
    @Transaction
    open suspend fun eliminarMision(m: MisionEntity) {
        deleteParametros(m.parametrosId)
    }

    @Query("UPDATE misiones SET estado = :estado, fechaActualizacion = :ahora WHERE id = :id")
    abstract suspend fun updateEstado(id: Long, estado: EstadoMision, ahora: Long)

    @Query("UPDATE misiones SET rutaWaypointsJson = :json, estado = :estado, fotosEstimadas = :fotos, duracionEstimadaMin = :duracion, fechaActualizacion = :ahora WHERE id = :id")
    abstract suspend fun updateRuta(
        id: Long,
        json: String,
        fotos: Int,
        duracion: Double,
        estado: EstadoMision,
        ahora: Long
    )

    @Transaction
    @Query("SELECT * FROM misiones ORDER BY fechaActualizacion DESC")
    abstract fun observeMisiones(): Flow<List<MisionCompleta>>

    @Transaction
    @Query("SELECT * FROM misiones WHERE id = :id")
    abstract fun observeMision(id: Long): Flow<MisionCompleta?>

    @Transaction
    @Query("SELECT * FROM misiones WHERE id = :id")
    abstract suspend fun getMision(id: Long): MisionCompleta?

    @Query("SELECT * FROM misiones WHERE loteId = :loteId ORDER BY fechaActualizacion DESC LIMIT 1")
    abstract suspend fun ultimaMisionDeLote(loteId: Long): MisionEntity?
}

/**
 * Acceso a `vuelos` y `salidas`: el historial de ejecuciones de cada misión.
 */
@Dao
abstract class VueloDao {

    @Insert
    abstract suspend fun insertVuelo(v: VueloEntity): Long

    @Update
    abstract suspend fun updateVuelo(v: VueloEntity)

    @Insert
    abstract suspend fun insertSalida(s: SalidaEntity): Long

    @Update
    abstract suspend fun updateSalida(s: SalidaEntity)

    @Query("SELECT * FROM vuelos WHERE id = :id")
    abstract suspend fun getVuelo(id: Long): VueloEntity?

    @Query("SELECT * FROM salidas WHERE id = :id")
    abstract suspend fun getSalida(id: Long): SalidaEntity?

    @Transaction
    @Query("SELECT * FROM vuelos WHERE misionId = :misionId ORDER BY fechaInicio DESC")
    abstract fun observeVuelos(misionId: Long): Flow<List<VueloCompleto>>

    /** El vuelo sin terminar de una misión (a lo sumo uno). */
    @Transaction
    @Query("SELECT * FROM vuelos WHERE misionId = :misionId AND estado = 'EN_CURSO' ORDER BY fechaInicio DESC LIMIT 1")
    abstract fun observeVueloActivo(misionId: Long): Flow<VueloCompleto?>

    /** Cierra los vuelos que quedaron EN_CURSO de una misión (al empezar uno nuevo). */
    @Query("UPDATE vuelos SET estado = 'INTERRUMPIDO', fechaFin = :ahora WHERE misionId = :misionId AND estado = 'EN_CURSO'")
    abstract suspend fun cerrarVuelosAbiertos(misionId: Long, ahora: Long)

    /** Una salida que quedó EN_CURSO porque la app se cerró en pleno vuelo. */
    @Query("UPDATE salidas SET estado = 'INTERRUMPIDA', motivo = :motivo, finMs = :ahora WHERE estado = 'EN_CURSO'")
    abstract suspend fun cerrarSalidasHuerfanas(motivo: String, ahora: Long)
}
