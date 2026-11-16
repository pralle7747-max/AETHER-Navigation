package com.samsungcam.app

/** Eine KI-Erkennung (Sensor-Koordinaten). [id] = Tracking-ID des Detektors, falls vorhanden. */
data class Det(val id: Int?, val label: String?, val box: Box)

/**
 * Führt Nutzer-Auswahl, KI-Erkennung (Klasse + stabile ID) und Vorlagen-Tracker zusammen und liefert eine
 * geglättete Objekt-Box. Ohne KI-Treffer läuft alles nur mit Vorlagen-Tracker + Filter weiter.
 */
class TargetFusion(private val sensorW: Int, private val sensorH: Int) {
    enum class Mode { IDLE, TEMPLATE, AI }

    private val filter = SubjectFilter(sensorW, sensorH)
    @Volatile var mode = Mode.IDLE; private set
    var label: String? = null; private set
    private var targetId: Int? = null
    private var selection: Box? = null
    private var lastMeasurementMs = Long.MIN_VALUE
    private var lastTemplate: Box? = null
    private var reseed: Box? = null

    val active get() = mode != Mode.IDLE

    /** Nutzer hat ein Objekt gewählt ([sel] in Sensor-Koordinaten). */
    fun begin(sel: Box, nowMs: Long) {
        mode = Mode.TEMPLATE; label = null; targetId = null; selection = sel
        lastTemplate = null; reseed = null
        filter.reset(sel, nowMs); lastMeasurementMs = nowMs
    }

    fun clear() { mode = Mode.IDLE; label = null; targetId = null; selection = null; reseed = null; lastTemplate = null }

    /** Messung des Vorlagen-Trackers (nur aufrufen, wenn gefunden). */
    fun onTemplate(box: Box, nowMs: Long) {
        if (!active) return
        filter.predict(nowMs); filter.update(box, 0.02)
        lastTemplate = box; lastMeasurementMs = nowMs
    }

    /** Ergebnisse des KI-Detektors für einen (etwas älteren) Frame. */
    fun onDetections(dets: List<Det>, nowMs: Long) {
        if (!active) return
        filter.predict(nowMs)
        val hit: Det? = when {
            selection != null -> matchSelection(selection!!, dets)
            targetId != null -> dets.firstOrNull { it.id == targetId } ?: reassociate(dets)
            else -> reassociate(dets)
        }
        if (hit == null) return
        if (selection != null) { // erste Zuordnung -> sperren
            selection = null; mode = Mode.AI; label = hit.label; targetId = hit.id
            filter.reset(hit.box, nowMs)
            reseed = hit.box
        } else {
            targetId = hit.id ?: targetId
            if (hit.label != null) label = hit.label
            filter.update(hit.box, 0.008)
            // Vorlage nachführen, wenn sie vom KI-Ergebnis wegdriftet
            val t = lastTemplate
            if (t == null || iou(t, hit.box) < 0.5f) reseed = hit.box
        }
        lastMeasurementMs = nowMs
    }

    /** Box, mit der die Vorlage neu gesetzt werden soll (einmalig abrufbar). */
    fun takeReseed(): Box? = reseed.also { reseed = null }

    fun current(nowMs: Long): Box? {
        if (!active) return null
        filter.predict(nowMs)
        return if (isLost(nowMs)) null else filter.box()
    }

    fun isLost(nowMs: Long) = active && nowMs - lastMeasurementMs > 600

    /** Letzte bekannte (geglättete) Box, auch wenn verloren. */
    fun lastKnown(): Box? = if (active) filter.box() else null

    private fun matchSelection(sel: Box, dets: List<Det>): Det? {
        val byIou = dets.filter { iou(it.box, sel) >= 0.15f }.maxByOrNull { iou(it.box, sel) }
        if (byIou != null) return byIou
        // Antippen: kleinste Erkennung, die den Mittelpunkt der Auswahl enthält
        return dets.filter { sel.cx in it.box.l..it.box.r && sel.cy in it.box.t..it.box.b && it.box.w * it.box.h < 0.7f * sensorW * sensorH }
            .minByOrNull { it.box.w * it.box.h }
    }

    private fun reassociate(dets: List<Det>): Det? {
        val pred = filter.box()
        return dets.filter { label == null || it.label == label }
            .map { it to iou(it.box, pred) }.filter { it.second >= 0.25f }.maxByOrNull { it.second }?.first
    }
}
