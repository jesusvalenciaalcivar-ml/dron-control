package com.jesus.dronplataneras.planning

/**
 * Combinaciones habituales de traslape. Más traslape = cada punto del terreno aparece en más
 * pasadas: la costura del ortomosaico es más segura (sobre todo en cultivo repetitivo como el
 * plátano) pero se vuela y graba más.
 */
enum class OverlapPreset(val label: String, val sidePct: Double, val frontPct: Double, val hint: String) {
    FAST("Rápido", 60.0, 70.0, "Menos vuelo y menos video; la costura del mapa es menos segura"),
    STANDARD("Estándar", 70.0, 75.0, "Equilibrio recomendado para un ortomosaico 2D"),
    DETAILED("Detallado", 80.0, 85.0, "Más seguro en cultivo muy repetitivo; bastante más vuelo");

    companion object {
        /** El perfil que coincide exactamente con estos valores; null si son personalizados. */
        fun match(sidePct: Double, frontPct: Double): OverlapPreset? =
            entries.firstOrNull { it.sidePct == sidePct && it.frontPct == frontPct }
    }
}
