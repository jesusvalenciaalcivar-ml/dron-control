package com.jesus.dronplataneras.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val ES_EC: Locale = Locale.forLanguageTag("es-EC")
private val ES_ES: Locale = Locale.forLanguageTag("es-ES")

// SimpleDateFormat no es seguro entre hilos: se crea uno por llamada (son pocas y baratas).

/** "7 oct, 14:05". */
fun formatDayTime(millis: Long): String = SimpleDateFormat("d MMM, HH:mm", ES_EC).format(Date(millis))

/** "7 oct 2026, 14:05". */
fun formatFullDateTime(millis: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", ES_EC).format(Date(millis))

/** "07/10 14:05", con la configuración regional del teléfono. */
fun formatShortDateTime(millis: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(millis))

/** "7 de octubre de 2026". */
fun formatLongDate(millis: Long): String = SimpleDateFormat("d 'de' MMMM 'de' yyyy", ES_ES).format(Date(millis))

/** "Hoy, 14:05", "Ayer, 14:05" o la fecha corta. */
fun friendlyDate(millis: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.timeInMillis = millis
    val day = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    val hour = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    cal.add(Calendar.DAY_OF_YEAR, 1)
    val nextDay = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    return when {
        day == today -> "Hoy, $hour"
        nextDay == today -> "Ayer, $hour"
        else -> formatDayTime(millis)
    }
}

/** "12m 05s" o "1h 20m". */
fun formatDuration(minutes: Double): String {
    val totalS = (minutes * 60).toInt()
    val m = totalS / 60
    val s = totalS % 60
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m ${"%02d".format(s)}s"
}

/** "1,20 ha (12 004 m²)" — en lotes pequeños los m² son más fáciles de comparar en campo. */
fun formatArea(hectares: Double): String =
    "%.2f ha (%,.0f m²)".format(hectares, hectares * 10_000)
