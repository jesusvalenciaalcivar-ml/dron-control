package com.jesus.dronplataneras.data

import android.content.Context
import androidx.room.withTransaction
import com.jesus.dronplataneras.mission.WaypointPlan
import com.jesus.dronplataneras.survey.GeoCoord
import com.jesus.dronplataneras.survey.LatLon
import com.jesus.dronplataneras.survey.PolygonGeometry
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Punto único de acceso a los datos locales para las pantallas. */
class AgroRepository private constructor(private val db: AgroDatabase) {

    private val lotes = db.loteDao()
    private val misiones = db.misionDao()
    private val vuelos = db.vueloDao()

    /**
     * Lotes con su perímetro, del más reciente al más antiguo. Se vuelve a emitir cuando la base cambia.
     */
    fun observeLotes(): Flow<List<LoteConVertices>> = lotes.observeLotes()
    /**
     * Misiones con su lote y parámetros, de la actualizada más recientemente a la más antigua.
     */
    fun observeMisiones(): Flow<List<MisionCompleta>> = misiones.observeMisiones()
    /**
     * Una misión con su lote y parámetros; emite null si se borra.
     */
    fun observeMision(id: Long): Flow<MisionCompleta?> = misiones.observeMision(id)
    /**
     * Lectura puntual de una misión.
     */
    suspend fun getMision(id: Long) = misiones.getMision(id)
    /**
     * Lectura puntual de un lote con su perímetro.
     */
    suspend fun getLote(id: Long) = lotes.getLote(id)
    /**
     * La misión más reciente del lote, o null si todavía no tiene ninguna.
     */
    suspend fun ultimaMisionDeLote(loteId: Long) = misiones.ultimaMisionDeLote(loteId)

    /**
     * Guarda un lote nuevo con su perímetro en una sola transacción y devuelve su id.
     * Exige al menos 3 vértices y un polígono que no se cruce a sí mismo.
     */
    suspend fun guardarLote(nombre: String, puntos: List<GeoCoord>): Long {
        require(puntos.size >= 3) { "Se necesitan al menos 3 vértices" }
        require(!PolygonGeometry.isSelfIntersecting(puntos)) { "El polígono se cruza a sí mismo" }
        val lote = LoteEntity(nombre = nombre.trim(), areaEstimadaHa = PolygonGeometry.areaSquareMeters(puntos) / 10_000)
        return lotes.insertLoteConVertices(lote, puntos)
    }

    /**
     * Reemplaza el perímetro de un lote conservando su id y sus misiones.
     */
    suspend fun actualizarLote(lote: LoteEntity, puntos: List<GeoCoord>) {
        require(puntos.size >= 3) { "Se necesitan al menos 3 vértices" }
        require(!PolygonGeometry.isSelfIntersecting(puntos)) { "El polígono se cruza a sí mismo" }
        lotes.updateLoteConVertices(lote.copy(areaEstimadaHa = PolygonGeometry.areaSquareMeters(puntos) / 10_000), puntos)
    }

    /**
     * Crea o actualiza la misión de planificación. Si cambian los parámetros de una misión que ya
     * tenía ruta, la ruta deja de corresponder: se descarta y vuelve a BORRADOR.
     */
    suspend fun guardarPlanificacion(
        misionId: Long?,
        nombre: String,
        loteId: Long,
        parametros: ParametrosVueloEntity,
        duracionMin: Double,
        fotos: Int,
        /** true si cambió el perímetro: la ruta guardada ya no corresponde al lote. */
        invalidarRuta: Boolean = false
    ): Long {
        val existente = misionId?.let { misiones.getMision(it) }
        return if (existente == null) {
            misiones.crearMision(
                parametros,
                MisionEntity(
                    nombre = nombre.trim(), loteId = loteId, parametrosId = 0,
                    duracionEstimadaMin = duracionMin, fotosEstimadas = fotos
                )
            )
        } else {
            val cambiaron = invalidarRuta || existente.parametros.copy(id = 0) != parametros.copy(id = 0)
            val m = existente.mision
            misiones.actualizarMision(
                parametros.copy(id = m.parametrosId),
                m.copy(
                    nombre = nombre.trim(),
                    duracionEstimadaMin = duracionMin,
                    fotosEstimadas = fotos,
                    rutaWaypointsJson = if (cambiaron) null else m.rutaWaypointsJson,
                    estado = if (cambiaron) EstadoMision.BORRADOR else m.estado
                )
            )
            m.id
        }
    }

    /**
     * Guarda la ruta generada o importada de una misión y la deja SINCRONIZADA (lista para volar).
     */
    suspend fun guardarRuta(misionId: Long, plan: WaypointPlan, fotos: Int, duracionMin: Double) =
        misiones.updateRuta(misionId, plan.toJson(), fotos, duracionMin, EstadoMision.SINCRONIZADO, System.currentTimeMillis())

    /**
     * Borra la misión y sus parámetros; sus vuelos y salidas se borran en cascada.
     */
    suspend fun eliminarMision(m: MisionEntity) = misiones.eliminarMision(m)

    // ------------------------------------------------------------------ vuelos (historial de ejecuciones)

    /**
     * Historial de vuelos de una misión, del más reciente al más antiguo, cada uno con sus salidas.
     */
    fun observeVuelos(misionId: Long): Flow<List<VueloCompleto>> = vuelos.observeVuelos(misionId)
    /**
     * El vuelo sin terminar de la misión (a lo sumo uno), para retomarlo después de cambiar la batería.
     */
    fun observeVueloActivo(misionId: Long): Flow<VueloCompleto?> = vuelos.observeVueloActivo(misionId)
    /**
     * Lectura puntual de un vuelo.
     */
    suspend fun getVuelo(id: Long) = vuelos.getVuelo(id)

    /**
     * Abre un vuelo nuevo de la misión con una copia del perímetro y la ruta tal como están ahora.
     * Si había otro vuelo sin terminar, se da por interrumpido: solo puede haber uno en curso.
     */
    suspend fun iniciarVuelo(mision: MisionCompleta, plan: WaypointPlan, desdeWaypoint: Int = 0): Long = db.withTransaction {
        val ahora = System.currentTimeMillis()
        vuelos.cerrarVuelosAbiertos(mision.mision.id, ahora)
        vuelos.insertVuelo(
            VueloEntity(
                misionId = mision.mision.id,
                modoCaptura = plan.captureMode,
                perimetroJson = perimetroToJson(mision.lote.perimetro()),
                rutaJson = plan.toJson(),
                totalWaypoints = plan.waypoints.size,
                siguienteWaypoint = desdeWaypoint,
                fechaInicio = ahora
            )
        )
    }

    /** Registra el inicio de una salida (un despegue). Devuelve su id. */
    suspend fun abrirSalida(vueloId: Long, indice: Int, desde: Int, hasta: Int, bateria: Int, logPath: String?): Long =
        vuelos.insertSalida(
            SalidaEntity(
                vueloId = vueloId, indice = indice, desdeWaypoint = desde, hastaWaypoint = hasta,
                bateriaInicio = bateria, logPath = logPath
            )
        )

    /** Datos con los que se cierra una salida. */
    data class CierreSalida(
        val completada: Boolean,
        val motivo: String,
        val bateriaFin: Int,
        val videoInicioMs: Long?,
        val videoFinMs: Long?,
        val distanciaM: Double,
        /** Primer waypoint que queda por volar después de esta salida. */
        val siguienteWaypoint: Int
    )

    /**
     * Cierra la salida y actualiza el vuelo: avanza el punto de reanudación y, si ya no queda ruta,
     * lo da por completado (y la misión también).
     */
    suspend fun cerrarSalida(salidaId: Long, c: CierreSalida): VueloEntity? = db.withTransaction {
        val ahora = System.currentTimeMillis()
        val salida = vuelos.getSalida(salidaId) ?: return@withTransaction null
        vuelos.updateSalida(
            salida.copy(
                estado = if (c.completada) EstadoSalida.COMPLETADA else EstadoSalida.INTERRUMPIDA,
                motivo = c.motivo, finMs = ahora, bateriaFin = c.bateriaFin,
                videoInicioMs = c.videoInicioMs, videoFinMs = c.videoFinMs, distanciaM = c.distanciaM
            )
        )
        val vuelo = vuelos.getVuelo(salida.vueloId) ?: return@withTransaction null
        val completo = c.siguienteWaypoint >= vuelo.totalWaypoints - 1
        val actualizado = vuelo.copy(
            siguienteWaypoint = c.siguienteWaypoint,
            estado = if (completo) EstadoVuelo.COMPLETADO else vuelo.estado,
            fechaFin = if (completo) ahora else vuelo.fechaFin
        )
        vuelos.updateVuelo(actualizado)
        if (completo) misiones.updateEstado(vuelo.misionId, EstadoMision.COMPLETADO, ahora)
        actualizado
    }

    /** El operador da por terminado el vuelo aunque falte ruta (p. ej. no hay más baterías). */
    suspend fun terminarVuelo(vueloId: Long) {
        val v = vuelos.getVuelo(vueloId) ?: return
        if (v.estado == EstadoVuelo.EN_CURSO) {
            vuelos.updateVuelo(v.copy(estado = EstadoVuelo.INTERRUMPIDO, fechaFin = System.currentTimeMillis()))
        }
    }

    /** Si la app se cerró en pleno vuelo, la salida quedó EN_CURSO para siempre: se marca interrumpida. */
    suspend fun cerrarSalidasHuerfanas() =
        vuelos.cerrarSalidasHuerfanas("La app se cerró durante la salida", System.currentTimeMillis())

    private fun perimetroToJson(pts: List<LatLon>): String =
        JSONArray().apply { pts.forEach { put(JSONArray().put(it.latitude).put(it.longitude)) } }.toString()

    /** Exporta el lote como GeoJSON (RFC 7946) para la aplicación de escritorio. */
    fun exportarLoteGeoJson(lote: LoteConVertices, parametros: ParametrosVueloEntity?, out: OutputStream) {
        val pts = lote.perimetro()
        val ring = if (PolygonGeometry.isCounterClockwise(pts)) pts else pts.reversed()
        val coords = JSONArray().apply {
            (ring + ring.first()).forEach { put(JSONArray().put(it.longitude).put(it.latitude)) }
        }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val feature = JSONObject().apply {
            put("type", "Feature")
            put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(coords)))
            put("properties", JSONObject().apply {
                put("lote_id", lote.lote.id)
                put("lote_uuid", lote.lote.uuid)
                put("name", lote.lote.nombre)
                put("crop", "platano")
                put("created_at", iso.format(Date(lote.lote.fechaCreacion)))
                put("area_m2", PolygonGeometry.areaSquareMeters(pts))
                put("perimeter_m", PolygonGeometry.perimeterMeters(pts))
                parametros?.let {
                    put("altura_m", it.alturaMetros)
                    put("traslape_pct", it.traslapePorcentaje)
                    put("margen_interno_m", it.margenInternoMetros)
                }
            })
        }
        val fc = JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(feature))
        out.bufferedWriter().use { it.write(fc.toString(2)) }
    }

    companion object {
        @Volatile private var instance: AgroRepository? = null

        fun get(context: Context): AgroRepository =
            instance ?: synchronized(this) {
                instance ?: AgroRepository(AgroDatabase.get(context)).also { instance = it }
            }
    }
}
