package com.jesus.dronplataneras.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.jesus.dronplataneras.mission.CaptureMode
import com.jesus.dronplataneras.survey.LatLon
import java.util.UUID

/**
 * Identificador global para duplicar el historial en el escritorio: los ids autoincrementales de
 * Room chocan entre dispositivos, el UUID no.
 */
fun newUuid(): String = UUID.randomUUID().toString()

/**
 * Un lote (polígono) a mapear. Su perímetro está en [VerticeEntity].
 */
@Entity(tableName = "lotes", indices = [Index(value = ["uuid"], unique = true)])
data class LoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val areaEstimadaHa: Double,
    /** epoch millis */
    val fechaCreacion: Long = System.currentTimeMillis(),
    val uuid: String = newUuid()
)

/**
 * Un vértice del perímetro de un lote. El orden de captura es parte de la geometría ([ordenSecuencia]).
 */
@Entity(
    tableName = "vertices",
    foreignKeys = [ForeignKey(
        entity = LoteEntity::class,
        parentColumns = ["id"],
        childColumns = ["loteId"],
        onDelete = ForeignKey.CASCADE
    )],
    // El orden es parte de la geometría: dos vértices del mismo lote no pueden compartir posición
    indices = [Index(value = ["loteId", "ordenSecuencia"], unique = true)]
)
data class VerticeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loteId: Long,
    val ordenSecuencia: Int,
    val latitud: Double,
    val longitud: Double
)

/**
 * Parámetros con los que se planifica la ruta de una misión (uno por misión).
 */
@Entity(tableName = "parametros_vuelo")
data class ParametrosVueloEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alturaMetros: Double,
    /** Traslape lateral entre pasadas. */
    val traslapePorcentaje: Double,
    val margenInternoMetros: Double,
    /** Altura del cultivo: el traslape se calcula en la copa, no en el suelo. */
    val alturaCopaMetros: Double = 4.0,
    /** Traslape entre cuadros consecutivos (en video solo define cada cuánto se extrae un cuadro). */
    val traslapeFrontalPorcentaje: Double = 75.0,
    val modoCaptura: CaptureMode = CaptureMode.VIDEO
)

/**
 * Ciclo de vida del plan de una misión (no el de cada vuelo, que está en [EstadoVuelo]).
 */
enum class EstadoMision {
    /** Parámetros guardados, todavía sin ruta de waypoints. */
    BORRADOR,
    /** Tiene ruta (generada en el móvil o importada) lista para volar sin internet. */
    SINCRONIZADO,
    /** Se voló completa al menos una vez (el historial está en sus vuelos). */
    COMPLETADO
}

/**
 * El plan de un lote: parámetros y ruta de waypoints. Se puede volar varias veces; cada ejecución es un [VueloEntity].
 */
@Entity(
    tableName = "misiones",
    foreignKeys = [
        ForeignKey(entity = LoteEntity::class, parentColumns = ["id"], childColumns = ["loteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ParametrosVueloEntity::class, parentColumns = ["id"], childColumns = ["parametrosId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("loteId"), Index("parametrosId"), Index("estado"), Index(value = ["uuid"], unique = true)]
)
data class MisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Nombre de la ruta, p. ej. "Finca Sanchez - Sector 1". */
    val nombre: String,
    val loteId: Long,
    val parametrosId: Long,
    val estado: EstadoMision = EstadoMision.BORRADOR,
    val duracionEstimadaMin: Double,
    val fotosEstimadas: Int,
    /** WaypointPlan serializado (ver mission/WaypointPlan.kt). null mientras es borrador. */
    val rutaWaypointsJson: String? = null,
    val fechaCreacion: Long = System.currentTimeMillis(),
    val fechaActualizacion: Long = System.currentTimeMillis(),
    val uuid: String = newUuid()
)

/** Lote con su perímetro. */
data class LoteConVertices(
    @Embedded val lote: LoteEntity,
    @Relation(parentColumn = "id", entityColumn = "loteId")
    val vertices: List<VerticeEntity>
)

/**
 * Perímetro en orden. Room no garantiza el orden de @Relation: siempre reconstruir por ordenSecuencia.
 * (Extensión y no propiedad para que Room no intente mapearla como columna.)
 */
fun LoteConVertices.perimetro(): List<LatLon> =
    vertices.sortedBy { it.ordenSecuencia }.map { LatLon(it.latitud, it.longitud) }

/** Misión con su lote (y vértices) y sus parámetros, para listas y pantallas de detalle. */
data class MisionCompleta(
    @Embedded val mision: MisionEntity,
    @Relation(entity = LoteEntity::class, parentColumn = "loteId", entityColumn = "id")
    val lote: LoteConVertices,
    @Relation(parentColumn = "parametrosId", entityColumn = "id")
    val parametros: ParametrosVueloEntity
)


/**
 * Estado de una ejecución completa de la misión (puede necesitar varias baterías).
 */
enum class EstadoVuelo {
    /** Hay salidas por volar (o se está volando una). */
    EN_CURSO,
    /** Se cubrió toda la ruta. */
    COMPLETADO,
    /** El operador dio por terminado el vuelo sin cubrir toda la ruta. */
    INTERRUMPIDO
}

/**
 * Estado de un despegue (una batería).
 */
enum class EstadoSalida { EN_CURSO, COMPLETADA, INTERRUMPIDA }

/**
 * Una ejecución de una misión (se puede volar la misma misión varias veces). Guarda una copia del
 * perímetro y de la ruta tal como estaban al volar, para que editar el lote o la misión después no
 * altere el historial.
 */
@Entity(
    tableName = "vuelos",
    foreignKeys = [ForeignKey(entity = MisionEntity::class, parentColumns = ["id"], childColumns = ["misionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("misionId"), Index(value = ["uuid"], unique = true)]
)
data class VueloEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val misionId: Long,
    val estado: EstadoVuelo = EstadoVuelo.EN_CURSO,
    val fechaInicio: Long = System.currentTimeMillis(),
    val fechaFin: Long? = null,
    val modoCaptura: CaptureMode,
    /** Perímetro volado: `[[lat,lon],...]`. */
    val perimetroJson: String,
    /** WaypointPlan volado (JSON). */
    val rutaJson: String,
    val totalWaypoints: Int,
    /** Primer waypoint que falta por volar; cuando llega a [totalWaypoints] el vuelo está completo. */
    val siguienteWaypoint: Int = 0
)

/** Un despegue con una batería: cubre los waypoints [desdeWaypoint]..[hastaWaypoint] de la ruta. */
@Entity(
    tableName = "salidas",
    foreignKeys = [ForeignKey(entity = VueloEntity::class, parentColumns = ["id"], childColumns = ["vueloId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["vueloId", "indice"], unique = true), Index(value = ["uuid"], unique = true)]
)
data class SalidaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = newUuid(),
    val vueloId: Long,
    /** Orden de la salida dentro del vuelo, desde 0. */
    val indice: Int,
    val desdeWaypoint: Int,
    val hastaWaypoint: Int,
    val estado: EstadoSalida = EstadoSalida.EN_CURSO,
    /** Por qué terminó (texto para mostrar al operador). */
    val motivo: String? = null,
    val inicioMs: Long = System.currentTimeMillis(),
    val finMs: Long? = null,
    val bateriaInicio: Int,
    val bateriaFin: Int? = null,
    /** Marcas de tiempo (epoch UTC) del video de esta salida, para emparejarlo con el archivo de la microSD. */
    val videoInicioMs: Long? = null,
    val videoFinMs: Long? = null,
    val distanciaM: Double = 0.0,
    /** Ruta del CSV del registro de vuelo en el almacenamiento de la app. */
    val logPath: String? = null
)

/**
 * Una salida cerrada con lo que la PC necesita para guardar su registro de vuelo en la carpeta correcta y emparejarla con
 * su video de la microSD (lectura de [VueloDao.salidasParaEnviar], no es una tabla).
 */
data class SalidaParaEnviar(
    val salidaUuid: String,
    val indice: Int,
    val estado: EstadoSalida,
    val inicioMs: Long,
    val finMs: Long?,
    val videoInicioMs: Long?,
    val videoFinMs: Long?,
    val logPath: String?,
    val vueloUuid: String,
    val vueloInicioMs: Long,
    val modoCaptura: CaptureMode,
    val mision: String,
    val lote: String
)

/**
 * Un vuelo con todas sus salidas.
 */
data class VueloCompleto(
    @Embedded val vuelo: VueloEntity,
    @Relation(parentColumn = "id", entityColumn = "vueloId")
    val salidas: List<SalidaEntity>
)

/**
 * Salidas que de verdad despegaron (y gastaron una batería). Los intentos cancelados antes de volar
 * (cámara no lista, pre-vuelo) quedan en el historial como auditoría pero no cuentan como salida.
 */
fun VueloCompleto.salidasVoladas(): Int =
    salidas.count { it.estado == EstadoSalida.COMPLETADA || it.distanciaM > 0.0 }

/** true si el vuelo ya voló algo: solo entonces tiene sentido hablar de "vuelo en curso" entre baterías. */
fun VueloCompleto.yaVoloAlgo(): Boolean = salidasVoladas() > 0 || vuelo.siguienteWaypoint > 0
