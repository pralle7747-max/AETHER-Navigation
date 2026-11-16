package com.samsungcam.app

import java.util.Locale

/** Eine vom Gerät für Apps freigegebene Kamera-Linse. */
data class Lens(val id: String, val front: Boolean, val rel: Float, val megapixels: Float, val label: String)

object LensLabel {
    /**
     * [rel] = Brennweite (Kleinbild-Äquivalent) relativ zur Hauptkamera: 0,5 = Ultraweit, 2 = Tele.
     * Makro-Linsen sind klein (wenige MP) und liegen bei ca. 1×.
     */
    fun of(front: Boolean, rel: Float, megapixels: Float): String {
        if (front) return "Front"
        val x = String.format(Locale.GERMAN, "%.1f×", rel).replace(",0×", "×")
        return when {
            rel < 0.8f -> "$x Ultraweit"
            rel > 1.5f -> "$x Tele"
            megapixels in 0.1f..8.5f && rel in 0.8f..1.3f && megapixels < 8.5f && megapixels != 0f -> "Makro?"
            else -> "$x Haupt"
        }
    }

    /** Kleinbild-Äquivalent in mm aus Brennweite und Sensorbreite (beide mm). */
    fun equiv35(focalMm: Float, sensorWidthMm: Float): Float = focalMm * 36f / sensorWidthMm
}
