package com.hinnka.mycamera.livephoto

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Motion is in analysis pixels; correction is the translation applied to the decoded frame. */
internal object MotionPhotoMotion {
    data class Shift(val x: Float, val y: Float)
    private data class Match(val x: Int, val y: Int)

    fun estimate(previous: ByteArray, current: ByteArray, width: Int, height: Int): Shift? {
        val matches = ArrayList<Match>(24)
        val patch = 4
        val search = 7
        for (gy in 1..4) for (gx in 1..6) {
            val cx = width * gx / 7
            val cy = height * gy / 5
            if (cx < patch + search || cy < patch + search ||
                cx + patch + search >= width || cy + patch + search >= height) continue
            var texture = 0
            for (y in -patch..patch step 2) for (x in -patch..patch step 2) {
                texture += abs((previous[(cy + y) * width + cx + x].toInt() and 255) -
                    (previous[(cy + y) * width + cx + x + 1].toInt() and 255))
            }
            if (texture < 90) continue
            var best = Int.MAX_VALUE
            var second = Int.MAX_VALUE
            var bestX = 0
            var bestY = 0
            for (dy in -search..search) for (dx in -search..search) {
                var error = 0
                for (y in -patch..patch step 2) for (x in -patch..patch step 2) {
                    error += abs((previous[(cy + y) * width + cx + x].toInt() and 255) -
                        (current[(cy + y + dy) * width + cx + x + dx].toInt() and 255))
                }
                if (error < best) {
                    second = best
                    best = error
                    bestX = dx
                    bestY = dy
                } else if (error < second && (abs(dx - bestX) > 1 || abs(dy - bestY) > 1)) {
                    second = error
                }
            }
            if (best < 900 && best * 4 < second * 3) matches.add(Match(bestX, bestY))
        }
        if (matches.size < 5) return null
        val medianX = matches.map { it.x }.sorted()[matches.size / 2]
        val medianY = matches.map { it.y }.sorted()[matches.size / 2]
        val inliers = matches.filter { abs(it.x - medianX) <= 2 && abs(it.y - medianY) <= 2 }
        if (inliers.size < max(5, matches.size / 2)) return null
        return Shift(inliers.map { it.x }.average().toFloat(), inliers.map { it.y }.average().toFloat())
    }

    fun corrections(motions: List<Shift>, width: Int, height: Int): List<Shift>? {
        if (motions.size < 3) return null
        val positions = ArrayList<Shift>(motions.size + 1)
        positions.add(Shift(0f, 0f))
        for (motion in motions) {
            val last = positions.last()
            positions.add(Shift(last.x + motion.x, last.y + motion.y))
        }
        val marginX = width * 0.10f
        val marginY = height * 0.10f
        return positions.indices.map { i ->
            val first = max(0, i - 4)
            val last = min(positions.lastIndex, i + 4)
            val smoothX = (first..last).sumOf { positions[it].x.toDouble() } / (last - first + 1)
            val smoothY = (first..last).sumOf { positions[it].y.toDouble() } / (last - first + 1)
            val dx = smoothX.toFloat() - positions[i].x
            val dy = smoothY.toFloat() - positions[i].y
            if (abs(dx) > marginX || abs(dy) > marginY) return null
            Shift(dx, dy)
        }
    }

    fun mapPrimaryTimestamp(originalUs: Long, firstVideoPtsUs: Long): Long =
        (originalUs - firstVideoPtsUs).coerceAtLeast(0L)
}
