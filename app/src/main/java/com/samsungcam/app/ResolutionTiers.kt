package com.samsungcam.app

import kotlin.math.abs

/** Sucht aus den vom Gerät angebotenen JPEG-Größen die passende für 12 / 50 / 100 MP. */
object ResolutionTiers {
    val tiers = listOf(12, 50, 100)

    private fun range(mp: Int): LongRange = when (mp) {
        12 -> 10_000_000L..16_000_000L
        50 -> 40_000_000L..62_000_000L
        else -> 90_000_000L..125_000_000L
    }

    /** Größte 4:3-Größe in der Stufe (sonst größte beliebige), oder null wenn das Gerät sie nicht anbietet. */
    fun pick(sizes: List<Pair<Int, Int>>, mp: Int): Pair<Int, Int>? {
        val inRange = sizes.filter { (w, h) -> w.toLong() * h in range(mp) }
        if (inRange.isEmpty()) return null
        val fourThree = inRange.filter { (w, h) -> abs(w.toFloat() / h - 4f / 3f) < 0.02f }
        return (fourThree.ifEmpty { inRange }).maxBy { (w, h) -> w.toLong() * h }
    }
}
