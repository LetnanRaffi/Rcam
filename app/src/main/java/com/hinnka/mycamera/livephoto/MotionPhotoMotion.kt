package com.hinnka.mycamera.livephoto

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Image coordinates have their origin at the top left. Positive rotation is clockwise. */
internal object MotionPhotoMotion {
    data class Shift(
        val x: Float,
        val y: Float,
        val rotationRadians: Float = 0f,
        val scale: Float = 1f,
        val inlierRatio: Float = 1f,
    )

    private data class Match(val x: Float, val y: Float, val u: Float, val v: Float)
    private data class Model(val a: Double, val b: Double, val tx: Double, val ty: Double) {
        fun error(match: Match): Double {
            val dx = a * match.x - b * match.y + tx - match.u
            val dy = b * match.x + a * match.y + ty - match.v
            return hypot(dx, dy)
        }
    }

    fun estimate(previous: ByteArray, current: ByteArray, width: Int, height: Int): Shift? {
        require(previous.size >= width * height && current.size >= width * height)
        val matches = ArrayList<Match>(48)
        val radius = 3
        val search = 12
        // One strong corner per cell prevents a textured moving subject owning all tracks.
        for (gy in 0 until 6) for (gx in 0 until 8) {
            val left = max(radius + search + 1, width * gx / 8)
            val right = min(width - radius - search - 2, width * (gx + 1) / 8 - 1)
            val top = max(radius + search + 1, height * gy / 6)
            val bottom = min(height - radius - search - 2, height * (gy + 1) / 6 - 1)
            var featureX = -1
            var featureY = -1
            var strength = 0L
            for (y in top..bottom step 3) for (x in left..right step 3) {
                var xx = 0L
                var yy = 0L
                var xy = 0L
                for (dy in -2..2 step 2) for (dx in -2..2 step 2) {
                    val at = (y + dy) * width + x + dx
                    val vx = (previous[at + 1].toInt() and 255) - (previous[at - 1].toInt() and 255)
                    val vy = (previous[at + width].toInt() and 255) - (previous[at - width].toInt() and 255)
                    xx += vx * vx
                    yy += vy * vy
                    xy += vx * vy
                }
                val corner = (xx * yy - xy * xy) / (xx + yy + 1)
                if (corner > strength) {
                    strength = corner
                    featureX = x
                    featureY = y
                }
            }
            if (strength < 300 || featureX < 0) continue
            var best = Int.MAX_VALUE
            var second = Int.MAX_VALUE
            var bestDx = 0
            var bestDy = 0
            // Full-pixel search is needed for high-frequency scenes: a coarse search can
            // miss an odd-pixel displacement entirely and reject an otherwise exact match.
            for (dy in -search..search) for (dx in -search..search) {
                val error = patchError(previous, current, width, featureX, featureY, dx, dy, radius)
                if (error < best) {
                    if (abs(dx - bestDx) > 2 || abs(dy - bestDy) > 2) second = min(second, best)
                    best = error
                    bestDx = dx
                    bestDy = dy
                } else if ((abs(dx - bestDx) > 2 || abs(dy - bestDy) > 2) && error < second) {
                    second = error
                }
            }
            if (best > 49 * 42 || (second != Int.MAX_VALUE && best > second * 0.92)) continue
            matches.add(Match(featureX.toFloat(), featureY.toFloat(),
                (featureX + bestDx).toFloat(), (featureY + bestDy).toFloat()))
        }
        if (matches.size < 8) return null
        val cx = width / 2f
        val cy = height / 2f
        val centered = matches.map { Match(it.x - cx, it.y - cy, it.u - cx, it.v - cy) }
        var bestInliers: List<Match> = emptyList()
        var bestError = Double.POSITIVE_INFINITY
        // Deterministic two-point RANSAC for a four-degree-of-freedom similarity.
        for (i in centered.indices) for (j in i + 1 until centered.size) {
            val model = fromPair(centered[i], centered[j]) ?: continue
            val magnitude = hypot(model.a, model.b)
            if (magnitude !in 0.90..1.10) continue
            val inliers = centered.filter { model.error(it) <= 2.5 }
            val error = inliers.sumOf { model.error(it) }
            if (inliers.size > bestInliers.size ||
                (inliers.size == bestInliers.size && error < bestError)) {
                bestInliers = inliers
                bestError = error
            }
        }
        if (bestInliers.size < max(7, (centered.size * 0.40).toInt()) ||
            !hasCoverage(bestInliers, width, height)) return null
        val similarity = fit(bestInliers) ?: return null
        val tx = bestInliers.map { (it.u - it.x).toDouble() }.sorted()[bestInliers.size / 2]
        val ty = bestInliers.map { (it.v - it.y).toDouble() }.sorted()[bestInliers.size / 2]
        val translation = Model(1.0, 0.0, tx, ty)
        val similarityError = bestInliers.sumOf { similarity.error(it) }
        val translationError = bestInliers.sumOf { translation.error(it) }
        // Do not fit tiny rotation and zoom to measurement noise.
        val selected = if (similarityError < translationError * 0.82) similarity else translation
        return Shift(selected.tx.toFloat(), selected.ty.toFloat(),
            atan2(selected.b, selected.a).toFloat(), hypot(selected.a, selected.b).toFloat(),
            bestInliers.size.toFloat() / matches.size)
    }

    private fun patchError(a: ByteArray, b: ByteArray, width: Int, x: Int, y: Int,
                           dx: Int, dy: Int, radius: Int): Int {
        var error = 0
        for (py in -radius..radius) for (px in -radius..radius) {
            val first = a[(y + py) * width + x + px].toInt() and 255
            val second = b[(y + py + dy) * width + x + px + dx].toInt() and 255
            error += abs(first - second)
        }
        return error
    }

    private fun fromPair(first: Match, second: Match): Model? {
        val dx = (second.x - first.x).toDouble()
        val dy = (second.y - first.y).toDouble()
        val denominator = dx * dx + dy * dy
        if (denominator < 400.0) return null
        val du = (second.u - first.u).toDouble()
        val dv = (second.v - first.v).toDouble()
        val a = (du * dx + dv * dy) / denominator
        val b = (dv * dx - du * dy) / denominator
        return Model(a, b, first.u - a * first.x + b * first.y,
            first.v - b * first.x - a * first.y)
    }

    private fun fit(matches: List<Match>): Model? {
        val mx = matches.sumOf { it.x.toDouble() } / matches.size
        val my = matches.sumOf { it.y.toDouble() } / matches.size
        val mu = matches.sumOf { it.u.toDouble() } / matches.size
        val mv = matches.sumOf { it.v.toDouble() } / matches.size
        var denominator = 0.0
        var a = 0.0
        var b = 0.0
        for (m in matches) {
            val x = m.x - mx
            val y = m.y - my
            val u = m.u - mu
            val v = m.v - mv
            denominator += x * x + y * y
            a += x * u + y * v
            b += x * v - y * u
        }
        if (denominator < 1.0) return null
        a /= denominator
        b /= denominator
        return Model(a, b, mu - a * mx + b * my, mv - b * mx - a * my)
    }

    private fun hasCoverage(matches: List<Match>, width: Int, height: Int): Boolean =
        matches.maxOf { it.x } - matches.minOf { it.x } >= width * 0.35f &&
            matches.maxOf { it.y } - matches.minOf { it.y } >= height * 0.30f

    /**
     * Huber acceleration minimization under per-frame crop bounds. A constant velocity
     * pan has zero acceleration cost. Cuts split independent paths and cannot be smoothed over.
     */
    fun corrections(motions: List<Shift>, width: Int, height: Int,
                    primaryFrame: Int = -1, cuts: Set<Int> = emptySet()): List<Shift>? {
        if (motions.size < 3) return null
        val result = ArrayList<Shift>(motions.size + 1)
        val boundaries = (listOf(0) + cuts.filter { it in 1..motions.size }.sorted() +
            listOf(motions.size + 1))
        for (segment in 0 until boundaries.lastIndex) {
            val first = boundaries[segment]
            val end = boundaries[segment + 1]
            val n = end - first
            if (n == 1) {
                result.add(Shift(0f, 0f))
                continue
            }
            val path = Array(4) { DoubleArray(n) }
            for (i in 1 until n) {
                val step = motions[first + i - 1]
                val c = cos(step.rotationRadians.toDouble()) * step.scale
                val s = sin(step.rotationRadians.toDouble()) * step.scale
                path[0][i] = c * path[0][i - 1] - s * path[1][i - 1] + step.x
                path[1][i] = s * path[0][i - 1] + c * path[1][i - 1] + step.y
                path[2][i] = path[2][i - 1] + step.rotationRadians
                path[3][i] = path[3][i - 1] + ln(step.scale.toDouble())
            }
            // Each scene starts from its own unwarped frame; the shutter frame takes
            // precedence when it lies inside that scene.
            val anchor = if (primaryFrame in first until end) primaryFrame - first else 0
            val limits = doubleArrayOf(width * .075, height * .075, .05, .035)
            val virtual = Array(4) { dim -> optimize(path[dim], limits[dim], anchor) }
            for (i in 0 until n) {
                val angle = virtual[2][i] - path[2][i]
                val scale = exp(virtual[3][i] - path[3][i])
                val c = cos(angle) * scale
                val s = sin(angle) * scale
                val tx = virtual[0][i] - c * path[0][i] + s * path[1][i]
                val ty = virtual[1][i] - s * path[0][i] - c * path[1][i]
                if (!tx.isFinite() || !ty.isFinite() ||
                    abs(tx) + abs(angle) * height / 2 + abs(scale - 1) * width / 2 > width * .10 ||
                    abs(ty) + abs(angle) * width / 2 + abs(scale - 1) * height / 2 > height * .10)
                    return null
                result.add(Shift(tx.toFloat(), ty.toFloat(), angle.toFloat(), scale.toFloat()))
            }
        }
        return result
    }

    private fun optimize(source: DoubleArray, limit: Double, anchor: Int): DoubleArray {
        val result = source.copyOf()
        repeat(120) {
            val gradient = DoubleArray(result.size)
            for (i in 1 until result.lastIndex) {
                val acceleration = result[i - 1] - 2 * result[i] + result[i + 1]
                val derivative = acceleration.coerceIn(-1.0, 1.0) * 3.0
                gradient[i - 1] += derivative
                gradient[i] -= 2 * derivative
                gradient[i + 1] += derivative
            }
            for (i in result.indices) {
                gradient[i] += (result[i] - source[i]) * .18
                result[i] = (result[i] - .065 * gradient[i])
                    .coerceIn(source[i] - limit, source[i] + limit)
            }
            if (anchor in result.indices) result[anchor] = source[anchor]
        }
        return result
    }

    fun mapPrimaryTimestamp(originalUs: Long, firstVideoPtsUs: Long): Long =
        (originalUs - firstVideoPtsUs).coerceAtLeast(0L)
}
