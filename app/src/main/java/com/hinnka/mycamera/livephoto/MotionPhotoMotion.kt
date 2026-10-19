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
import kotlin.math.sqrt

/** Image coordinates have their origin at the top left. Positive rotation is clockwise. */
internal object MotionPhotoMotion {
    enum class ModelKind { TRANSLATION, SIMILARITY, AFFINE }

    /** Forward transform from a decoded source frame into the stabilized output frame. */
    data class Shift(
        val x: Float,
        val y: Float,
        val rotationRadians: Float = 0f,
        val scale: Float = 1f,
        val inlierRatio: Float = 1f,
        val stretch: Float = 0f,
        val shear: Float = 0f,
        val residual: Float = 0f,
        val model: ModelKind = ModelKind.TRANSLATION,
    ) {
        fun linear(): FloatArray {
            val c = cos(rotationRadians) * scale
            val s = sin(rotationRadians) * scale
            return floatArrayOf(c + stretch, -s + shear, s + shear, c - stretch)
        }

        fun inverseLinear(): FloatArray? {
            val m = linear()
            val determinant = m[0] * m[3] - m[1] * m[2]
            if (!determinant.isFinite() || abs(determinant) < 0.35f) return null
            return floatArrayOf(
                m[3] / determinant,
                -m[1] / determinant,
                -m[2] / determinant,
                m[0] / determinant,
            )
        }
    }

    private data class GrayImage(val width: Int, val height: Int, val values: FloatArray)
    private data class Point(val x: Float, val y: Float)
    private data class Match(val x: Double, val y: Double, val u: Double, val v: Double)
    private data class Affine(
        val m00: Double,
        val m01: Double,
        val m10: Double,
        val m11: Double,
        val tx: Double,
        val ty: Double,
    ) {
        fun error(match: Match): Double = hypot(
            m00 * match.x + m01 * match.y + tx - match.u,
            m10 * match.x + m11 * match.y + ty - match.v,
        )

        fun determinant(): Double = m00 * m11 - m01 * m10
    }

    private data class Candidate(
        val transform: Affine,
        val inliers: List<Match>,
        val residual: Double,
        val kind: ModelKind,
    )

    fun estimate(
        previous: ByteArray,
        current: ByteArray,
        width: Int,
        height: Int,
        alignment: Boolean = false,
    ): Shift? {
        require(previous.size >= width * height && current.size >= width * height)
        val previousPyramid = pyramid(previous, width, height)
        val currentPyramid = pyramid(current, width, height)
        val features = detectFeatures(previousPyramid.first(), if (alignment) 80 else 64)
        if (features.size < 12) return null

        val matches = ArrayList<Match>(features.size)
        val maximumDisplacement = if (alignment) width * 0.28f else width * 0.13f
        for (feature in features) {
            val tracked = track(previousPyramid, currentPyramid, feature, feature) ?: continue
            if (hypot(tracked.x - feature.x, tracked.y - feature.y) > maximumDisplacement) continue
            val reverse = track(currentPyramid, previousPyramid, tracked, feature) ?: continue
            if (hypot(reverse.x - feature.x, reverse.y - feature.y) > 1.15f) continue
            if (patchCorrelation(previousPyramid.first(), currentPyramid.first(), feature, tracked) < 0.68f) continue
            matches += Match(
                feature.x - width * 0.5,
                feature.y - height * 0.5,
                tracked.x - width * 0.5,
                tracked.y - height * 0.5,
            )
        }
        if (matches.size < 10) return null

        val threshold = if (alignment) 2.2 else 1.65
        val similarity = similarityRansac(matches, width, height, threshold, alignment) ?: return null
        val translation = candidate(
            translationFit(similarity.inliers), matches, threshold, ModelKind.TRANSLATION,
        ) ?: return null
        var selected = if (
            similarity.residual < translation.residual * 0.78 &&
            similarity.inliers.size >= translation.inliers.size * 0.85
        ) similarity else translation

        if (matches.size >= 16) {
            val affine = affineRansac(matches, width, height, threshold, alignment)
            if (affine != null &&
                affine.inliers.size >= max(12, (selected.inliers.size * 0.88).toInt()) &&
                affine.residual < selected.residual * 0.72 &&
                affineIsSane(affine.transform, alignment)
            ) selected = affine
        }

        if (!hasCoverage(selected.inliers, width, height)) return null
        val ratio = selected.inliers.size.toFloat() / matches.size
        if (ratio < if (alignment) 0.34f else 0.42f) return null
        return selected.transform.toShift(ratio, selected.residual.toFloat(), selected.kind)
            .takeIf { shiftIsSane(it, width, height, alignment) }
    }

    /** A normalized correlation avoids treating a flash/exposure change as a scene cut. */
    fun isSceneCut(previous: ByteArray, current: ByteArray): Boolean {
        if (previous.size != current.size || previous.isEmpty()) return true
        var count = 0
        var sumA = 0.0
        var sumB = 0.0
        for (i in previous.indices step 4) {
            sumA += previous[i].toInt() and 255
            sumB += current[i].toInt() and 255
            count++
        }
        val meanA = sumA / count
        val meanB = sumB / count
        var covariance = 0.0
        var varianceA = 0.0
        var varianceB = 0.0
        for (i in previous.indices step 4) {
            val a = (previous[i].toInt() and 255) - meanA
            val b = (current[i].toInt() and 255) - meanB
            covariance += a * b
            varianceA += a * a
            varianceB += b * b
        }
        if (varianceA < 1_000.0 || varianceB < 1_000.0) return false
        return covariance / sqrt(varianceA * varianceB) < 0.08
    }

    /** Repairs at most two uncertain transitions without inserting an artificial zero-motion snap. */
    fun repair(motions: List<Shift?>, cuts: Set<Int>): List<Shift>? {
        val repaired = motions.toMutableList()
        var index = 0
        while (index < repaired.size) {
            if (cuts.contains(index + 1)) {
                repaired[index] = Shift(0f, 0f)
                index++
                continue
            }
            if (repaired[index] != null) {
                index++
                continue
            }
            val start = index
            while (index < repaired.size && repaired[index] == null && !cuts.contains(index + 1)) index++
            val end = index
            val unresolved = (start until end).filter { repaired[it] == null }
            if (unresolved.size > 2) return null
            val left = (start - 1 downTo 0).firstNotNullOfOrNull { repaired[it] }
            val right = (end until repaired.size).firstNotNullOfOrNull { repaired[it] }
            if (left == null && right == null) return null
            unresolved.forEachIndexed { offset, missing ->
                val amount = (offset + 1f) / (unresolved.size + 1f)
                repaired[missing] = when {
                    left != null && right != null -> interpolate(left, right, amount, 0.35f)
                    left != null -> left.copy(inlierRatio = left.inlierRatio * 0.35f)
                    else -> right!!.copy(inlierRatio = right.inlierRatio * 0.35f)
                }
            }
        }
        return repaired.mapIndexed { motionIndex, shift ->
            if (cuts.contains(motionIndex + 1)) Shift(0f, 0f) else shift ?: return null
        }
    }

    /**
     * Builds the complete camera path and solves a non-causal robust acceleration objective.
     * Frame intervals come from encoded PTS. The shutter frame is anchored to the still image.
     */
    fun corrections(
        motions: List<Shift>,
        width: Int,
        height: Int,
        primaryFrame: Int = -1,
        cuts: Set<Int> = emptySet(),
        framePtsUs: List<Long> = List(motions.size + 1) { it * 33_333L },
        photoAlignment: Shift? = null,
    ): List<Shift>? {
        if (motions.size < 3 || framePtsUs.size != motions.size + 1) return null
        val result = ArrayList<Shift>(motions.size + 1)
        val boundaries = (listOf(0) + cuts.filter { it in 1..motions.size }.sorted() +
            listOf(motions.size + 1)).distinct()
        for (segment in 0 until boundaries.lastIndex) {
            val first = boundaries[segment]
            val end = boundaries[segment + 1]
            val count = end - first
            if (count == 1) {
                result += Shift(0f, 0f)
                continue
            }

            val observed = ArrayList<Affine>(count)
            observed += identity()
            for (i in 1 until count) {
                observed += compose(motions[first + i - 1].toAffine(), observed.last())
            }
            val source = Array(6) { DoubleArray(count) }
            observed.forEachIndexed { i, transform ->
                val parameter = parameters(transform)
                for (dimension in source.indices) source[dimension][i] = parameter[dimension]
                if (i > 0) source[2][i] = unwrap(source[2][i - 1], source[2][i])
            }

            val anchor = (primaryFrame - first).takeIf { it in 0 until count }
            val alignment = if (anchor != null) photoAlignment ?: Shift(0f, 0f) else null
            val anchorParameters = if (anchor != null && alignment != null) {
                parameters(compose(alignment.toAffine(), observed[anchor]))
            } else null
            val times = framePtsUs.subList(first, end)
            val confidence = DoubleArray(count) { local ->
                val global = first + local
                val before = motions.getOrNull(global - 1)?.inlierRatio?.toDouble() ?: 1.0
                val after = motions.getOrNull(global)?.inlierRatio?.toDouble() ?: before
                (0.25 + 0.75 * min(before, after)).coerceIn(0.25, 1.0)
            }
            val limits = doubleArrayOf(width * .12, height * .12, .085, .065, .055, .055)
            val virtual = Array(6) { dimension ->
                smoothRobust(
                    source[dimension], times, confidence, limits[dimension],
                    anchor, anchorParameters?.get(dimension),
                )
            }

            for (i in 0 until count) {
                val virtualTransform = fromParameters(DoubleArray(6) { virtual[it][i] })
                val correction = compose(virtualTransform, inverse(observed[i]) ?: return null)
                    .toShift(model = ModelKind.AFFINE)
                if (!shiftIsSane(correction, width, height, alignment = true)) return null
                result += correction
            }
        }
        return result.takeIf { it.size == motions.size + 1 }
    }

    /** Reduces stabilization strength while retaining the optimized trajectory shape. */
    fun withStrength(corrections: List<Shift>, strength: Float): List<Shift> {
        val amount = strength.coerceIn(0f, 1f)
        return corrections.map { interpolate(Shift(0f, 0f), it, amount, it.inlierRatio) }
    }

    fun mapPrimaryTimestamp(originalUs: Long, firstVideoPtsUs: Long): Long =
        (originalUs - firstVideoPtsUs).coerceAtLeast(0L)

    private fun pyramid(bytes: ByteArray, width: Int, height: Int): List<GrayImage> {
        val levels = ArrayList<GrayImage>(3)
        levels += GrayImage(width, height,
            FloatArray(width * height) { (bytes[it].toInt() and 255).toFloat() })
        while (levels.size < 3 && levels.last().width >= 64 && levels.last().height >= 48) {
            val source = levels.last()
            val targetWidth = source.width / 2
            val targetHeight = source.height / 2
            val values = FloatArray(targetWidth * targetHeight)
            for (y in 0 until targetHeight) for (x in 0 until targetWidth) {
                val sx = x * 2
                val sy = y * 2
                var sum = 0f
                var weight = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    val px = (sx + dx).coerceIn(0, source.width - 1)
                    val py = (sy + dy).coerceIn(0, source.height - 1)
                    val w = if (dx == 0 && dy == 0) 4f else if (dx == 0 || dy == 0) 2f else 1f
                    sum += source.values[py * source.width + px] * w
                    weight += w
                }
                values[y * targetWidth + x] = sum / weight
            }
            levels += GrayImage(targetWidth, targetHeight, values)
        }
        return levels
    }

    private fun detectFeatures(image: GrayImage, maximum: Int): List<Point> {
        val candidates = ArrayList<Pair<Float, Point>>()
        val cellsX = 8
        val cellsY = 6
        val margin = 18
        for (cellY in 0 until cellsY) for (cellX in 0 until cellsX) {
            val left = max(margin, image.width * cellX / cellsX)
            val right = min(image.width - margin - 1, image.width * (cellX + 1) / cellsX - 1)
            val top = max(margin, image.height * cellY / cellsY)
            val bottom = min(image.height - margin - 1, image.height * (cellY + 1) / cellsY - 1)
            if (left >= right || top >= bottom) continue
            val local = ArrayList<Pair<Float, Point>>()
            for (y in top..bottom step 2) for (x in left..right step 2) {
                var xx = 0f
                var yy = 0f
                var xy = 0f
                for (dy in -2..2) for (dx in -2..2) {
                    val index = (y + dy) * image.width + x + dx
                    val gx = image.values[index + 1] - image.values[index - 1]
                    val gy = image.values[index + image.width] - image.values[index - image.width]
                    xx += gx * gx
                    yy += gy * gy
                    xy += gx * gy
                }
                val trace = xx + yy
                val eigen = 0.5f * (trace - sqrt(max(0f, (xx - yy) * (xx - yy) + 4f * xy * xy)))
                if (eigen > 850f) local += eigen to Point(x.toFloat(), y.toFloat())
            }
            local.sortedByDescending { it.first }.forEach { candidate ->
                val inCell = candidates.count { existing ->
                    existing.second.x in left.toFloat()..right.toFloat() &&
                        existing.second.y in top.toFloat()..bottom.toFloat()
                }
                if (inCell < 2 && candidates.none { squaredDistance(it.second, candidate.second) < 64f }) {
                    candidates += candidate
                }
            }
        }
        return candidates.sortedByDescending { it.first }.take(maximum).map { it.second }
    }

    private fun track(
        source: List<GrayImage>,
        target: List<GrayImage>,
        feature: Point,
        initial: Point,
    ): Point? {
        val levels = min(source.size, target.size)
        var qx = initial.x / (1 shl (levels - 1))
        var qy = initial.y / (1 shl (levels - 1))
        for (level in levels - 1 downTo 0) {
            val scale = (1 shl level).toFloat()
            val px = feature.x / scale
            val py = feature.y / scale
            if (level != levels - 1) {
                qx *= 2f
                qy *= 2f
            }
            val from = source[level]
            val to = target[level]
            var converged = false
            repeat(7) {
                if (converged) return@repeat
                if (!inside(from, px, py, 5f) || !inside(to, qx, qy, 6f)) return null
                var meanFrom = 0f
                var meanTo = 0f
                var samples = 0
                for (dy in -3..3) for (dx in -3..3) {
                    meanFrom += sample(from, px + dx, py + dy)
                    meanTo += sample(to, qx + dx, qy + dy)
                    samples++
                }
                meanFrom /= samples
                meanTo /= samples
                var varianceFrom = 0f
                var varianceTo = 0f
                for (dy in -3..3) for (dx in -3..3) {
                    val a = sample(from, px + dx, py + dy) - meanFrom
                    val b = sample(to, qx + dx, qy + dy) - meanTo
                    varianceFrom += a * a
                    varianceTo += b * b
                }
                val gain = sqrt((varianceFrom + 64f) / (varianceTo + 64f)).coerceIn(0.45f, 2.2f)
                var xx = 0f
                var yy = 0f
                var xy = 0f
                var bx = 0f
                var by = 0f
                for (dy in -3..3) for (dx in -3..3) {
                    val sx = qx + dx
                    val sy = qy + dy
                    val gx = (sample(to, sx + 1f, sy) - sample(to, sx - 1f, sy)) * 0.5f * gain
                    val gy = (sample(to, sx, sy + 1f) - sample(to, sx, sy - 1f)) * 0.5f * gain
                    val error = ((sample(to, sx, sy) - meanTo) * gain -
                        (sample(from, px + dx, py + dy) - meanFrom)).coerceIn(-36f, 36f)
                    xx += gx * gx
                    yy += gy * gy
                    xy += gx * gy
                    bx += gx * error
                    by += gy * error
                }
                val determinant = xx * yy - xy * xy
                if (determinant < 25_000f) return null
                val dx = ((xy * by - yy * bx) / determinant).coerceIn(-1.5f, 1.5f)
                val dy = ((xy * bx - xx * by) / determinant).coerceIn(-1.5f, 1.5f)
                qx += dx
                qy += dy
                converged = dx * dx + dy * dy < 0.0009f
            }
        }
        return Point(qx, qy).takeIf { inside(target.first(), qx, qy, 5f) }
    }

    private fun patchCorrelation(first: GrayImage, second: GrayImage, a: Point, b: Point): Float {
        var meanA = 0f
        var meanB = 0f
        var count = 0
        for (dy in -3..3) for (dx in -3..3) {
            meanA += sample(first, a.x + dx, a.y + dy)
            meanB += sample(second, b.x + dx, b.y + dy)
            count++
        }
        meanA /= count
        meanB /= count
        var covariance = 0f
        var varianceA = 0f
        var varianceB = 0f
        for (dy in -3..3) for (dx in -3..3) {
            val x = sample(first, a.x + dx, a.y + dy) - meanA
            val y = sample(second, b.x + dx, b.y + dy) - meanB
            covariance += x * y
            varianceA += x * x
            varianceB += y * y
        }
        return covariance / sqrt(max(1f, varianceA * varianceB))
    }

    private fun similarityRansac(
        matches: List<Match>, width: Int, height: Int, threshold: Double, alignment: Boolean,
    ): Candidate? {
        var best: Candidate? = null
        for (i in matches.indices) for (j in i + 1 until matches.size) {
            val hypothesis = similarityFromPair(matches[i], matches[j]) ?: continue
            if (!affineIsSane(hypothesis, alignment)) continue
            val evaluated = candidate(hypothesis, matches, threshold, ModelKind.SIMILARITY) ?: continue
            if (better(evaluated, best)) best = evaluated
        }
        var refined = best ?: return null
        repeat(3) {
            val fit = fitSimilarity(refined.inliers) ?: return@repeat
            refined = candidate(fit, matches, threshold, ModelKind.SIMILARITY) ?: refined
        }
        return refined.takeIf { it.inliers.size >= max(9, (matches.size * .38).toInt()) &&
            hasCoverage(it.inliers, width, height) }
    }

    private fun affineRansac(
        matches: List<Match>, width: Int, height: Int, threshold: Double, alignment: Boolean,
    ): Candidate? {
        var best: Candidate? = null
        val attempts = min(512, matches.size * matches.size)
        for (attempt in 0 until attempts) {
            val i = (attempt * 17 + 3) % matches.size
            var j = (attempt * 31 + 7) % matches.size
            var k = (attempt * 47 + 11) % matches.size
            if (j == i) j = (j + 1) % matches.size
            while (k == i || k == j) k = (k + 1) % matches.size
            val hypothesis = affineFromTriple(matches[i], matches[j], matches[k]) ?: continue
            if (!affineIsSane(hypothesis, alignment)) continue
            val evaluated = candidate(hypothesis, matches, threshold, ModelKind.AFFINE) ?: continue
            if (better(evaluated, best)) best = evaluated
        }
        var refined = best ?: return null
        repeat(4) {
            val fit = fitAffine(refined.inliers) ?: return@repeat
            if (!affineIsSane(fit, alignment)) return@repeat
            refined = candidate(fit, matches, threshold, ModelKind.AFFINE) ?: refined
        }
        return refined.takeIf { hasCoverage(it.inliers, width, height) }
    }

    private fun candidate(
        transform: Affine, matches: List<Match>, threshold: Double, kind: ModelKind,
    ): Candidate? {
        val inliers = matches.filter { transform.error(it) <= threshold }
        if (inliers.size < 3) return null
        val sorted = inliers.map(transform::error).sorted()
        return Candidate(transform, inliers, sorted[sorted.size / 2], kind)
    }

    private fun better(candidate: Candidate, current: Candidate?): Boolean = current == null ||
        candidate.inliers.size > current.inliers.size ||
        (candidate.inliers.size == current.inliers.size && candidate.residual < current.residual)

    private fun translationFit(matches: List<Match>): Affine {
        val tx = matches.map { it.u - it.x }.sorted()[matches.size / 2]
        val ty = matches.map { it.v - it.y }.sorted()[matches.size / 2]
        return Affine(1.0, 0.0, 0.0, 1.0, tx, ty)
    }

    private fun similarityFromPair(first: Match, second: Match): Affine? {
        val dx = second.x - first.x
        val dy = second.y - first.y
        val denominator = dx * dx + dy * dy
        if (denominator < 225.0) return null
        val du = second.u - first.u
        val dv = second.v - first.v
        val a = (du * dx + dv * dy) / denominator
        val b = (dv * dx - du * dy) / denominator
        return Affine(a, -b, b, a,
            first.u - a * first.x + b * first.y,
            first.v - b * first.x - a * first.y)
    }

    private fun fitSimilarity(matches: List<Match>): Affine? {
        val mx = matches.sumOf { it.x } / matches.size
        val my = matches.sumOf { it.y } / matches.size
        val mu = matches.sumOf { it.u } / matches.size
        val mv = matches.sumOf { it.v } / matches.size
        var denominator = 0.0
        var a = 0.0
        var b = 0.0
        for (match in matches) {
            val x = match.x - mx
            val y = match.y - my
            val u = match.u - mu
            val v = match.v - mv
            denominator += x * x + y * y
            a += x * u + y * v
            b += x * v - y * u
        }
        if (denominator < 1.0) return null
        a /= denominator
        b /= denominator
        return Affine(a, -b, b, a, mu - a * mx + b * my, mv - b * mx - a * my)
    }

    private fun affineFromTriple(a: Match, b: Match, c: Match): Affine? {
        val matrix = arrayOf(
            doubleArrayOf(a.x, a.y, 1.0),
            doubleArrayOf(b.x, b.y, 1.0),
            doubleArrayOf(c.x, c.y, 1.0),
        )
        val u = solve3(matrix, doubleArrayOf(a.u, b.u, c.u)) ?: return null
        val v = solve3(matrix, doubleArrayOf(a.v, b.v, c.v)) ?: return null
        return Affine(u[0], u[1], v[0], v[1], u[2], v[2])
    }

    private fun fitAffine(matches: List<Match>): Affine? {
        val normal = Array(3) { DoubleArray(3) }
        val rightU = DoubleArray(3)
        val rightV = DoubleArray(3)
        for (match in matches) {
            val row = doubleArrayOf(match.x, match.y, 1.0)
            for (i in 0..2) {
                rightU[i] += row[i] * match.u
                rightV[i] += row[i] * match.v
                for (j in 0..2) normal[i][j] += row[i] * row[j]
            }
        }
        val u = solve3(normal, rightU) ?: return null
        val v = solve3(normal, rightV) ?: return null
        return Affine(u[0], u[1], v[0], v[1], u[2], v[2])
    }

    private fun solve3(source: Array<DoubleArray>, right: DoubleArray): DoubleArray? {
        val matrix = Array(3) { row -> DoubleArray(4) { column ->
            if (column < 3) source[row][column] else right[row]
        } }
        for (pivot in 0..2) {
            val best = (pivot..2).maxBy { abs(matrix[it][pivot]) }
            if (abs(matrix[best][pivot]) < 1e-8) return null
            val swap = matrix[pivot]
            matrix[pivot] = matrix[best]
            matrix[best] = swap
            val divisor = matrix[pivot][pivot]
            for (column in pivot..3) matrix[pivot][column] /= divisor
            for (row in 0..2) if (row != pivot) {
                val factor = matrix[row][pivot]
                for (column in pivot..3) matrix[row][column] -= factor * matrix[pivot][column]
            }
        }
        return DoubleArray(3) { matrix[it][3] }
    }

    private fun affineIsSane(transform: Affine, alignment: Boolean): Boolean {
        val column0 = hypot(transform.m00, transform.m10)
        val column1 = hypot(transform.m01, transform.m11)
        val determinant = transform.determinant()
        val scaleLow = if (alignment) .70 else .86
        val scaleHigh = if (alignment) 1.38 else 1.16
        val dot = abs(transform.m00 * transform.m01 + transform.m10 * transform.m11)
        return determinant > 0.0 && column0 in scaleLow..scaleHigh && column1 in scaleLow..scaleHigh &&
            dot < if (alignment) .18 else .10
    }

    private fun shiftIsSane(shift: Shift, width: Int, height: Int, alignment: Boolean): Boolean {
        val linear = shift.linear()
        val determinant = linear[0] * linear[3] - linear[1] * linear[2]
        val translationLimit = if (alignment) .30 else .16
        return shift.x.isFinite() && shift.y.isFinite() && determinant in 0.42f..1.75f &&
            abs(shift.x) < width * translationLimit && abs(shift.y) < height * translationLimit &&
            abs(shift.rotationRadians) < (if (alignment) .28f else .16f) &&
            abs(shift.stretch) < (if (alignment) .16f else .08f) &&
            abs(shift.shear) < (if (alignment) .16f else .08f)
    }

    private fun hasCoverage(matches: List<Match>, width: Int, height: Int): Boolean {
        if (matches.size < 6) return false
        val spreadX = matches.maxOf { it.x } - matches.minOf { it.x }
        val spreadY = matches.maxOf { it.y } - matches.minOf { it.y }
        val occupied = BooleanArray(12)
        matches.forEach {
            val x = (((it.x + width * .5) / width * 4).toInt()).coerceIn(0, 3)
            val y = (((it.y + height * .5) / height * 3).toInt()).coerceIn(0, 2)
            occupied[y * 4 + x] = true
        }
        return spreadX >= width * .38 && spreadY >= height * .30 && occupied.count { it } >= 5
    }

    private fun Affine.toShift(
        inlierRatio: Float = 1f,
        residual: Float = 0f,
        model: ModelKind = ModelKind.AFFINE,
    ): Shift {
        val a = (m00 + m11) * .5
        val b = (m10 - m01) * .5
        val scale = hypot(a, b)
        return Shift(
            tx.toFloat(), ty.toFloat(), atan2(b, a).toFloat(), scale.toFloat(),
            inlierRatio, ((m00 - m11) * .5).toFloat(), ((m01 + m10) * .5).toFloat(),
            residual, model,
        )
    }

    private fun Shift.toAffine(): Affine {
        val matrix = linear()
        return Affine(
            matrix[0].toDouble(), matrix[1].toDouble(), matrix[2].toDouble(), matrix[3].toDouble(),
            x.toDouble(), y.toDouble(),
        )
    }

    private fun identity() = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    /** left(right(point)) */
    private fun compose(left: Affine, right: Affine): Affine = Affine(
        left.m00 * right.m00 + left.m01 * right.m10,
        left.m00 * right.m01 + left.m01 * right.m11,
        left.m10 * right.m00 + left.m11 * right.m10,
        left.m10 * right.m01 + left.m11 * right.m11,
        left.m00 * right.tx + left.m01 * right.ty + left.tx,
        left.m10 * right.tx + left.m11 * right.ty + left.ty,
    )

    private fun inverse(transform: Affine): Affine? {
        val determinant = transform.determinant()
        if (abs(determinant) < 1e-8) return null
        val m00 = transform.m11 / determinant
        val m01 = -transform.m01 / determinant
        val m10 = -transform.m10 / determinant
        val m11 = transform.m00 / determinant
        return Affine(m00, m01, m10, m11,
            -(m00 * transform.tx + m01 * transform.ty),
            -(m10 * transform.tx + m11 * transform.ty))
    }

    /** tx, ty, unwrapped rotation, log scale, stretch, shear. */
    private fun parameters(transform: Affine): DoubleArray {
        val shift = transform.toShift()
        return doubleArrayOf(
            shift.x.toDouble(), shift.y.toDouble(), shift.rotationRadians.toDouble(),
            ln(shift.scale.toDouble().coerceAtLeast(1e-5)), shift.stretch.toDouble(), shift.shear.toDouble(),
        )
    }

    private fun fromParameters(value: DoubleArray): Affine {
        val scale = exp(value[3])
        val c = cos(value[2]) * scale
        val s = sin(value[2]) * scale
        return Affine(c + value[4], -s + value[5], s + value[5], c - value[4], value[0], value[1])
    }

    private fun smoothRobust(
        source: DoubleArray,
        framePtsUs: List<Long>,
        confidence: DoubleArray,
        limit: Double,
        anchor: Int?,
        anchorValue: Double?,
    ): DoubleArray {
        if (source.size < 3 || limit <= 0.0) return source.copyOf()
        val normalized = DoubleArray(source.size) { source[it] / limit }
        val result = normalized.copyOf()
        val deltas = framePtsUs.zipWithNext { a, b -> (b - a).coerceAtLeast(1L).toDouble() }
        val medianDelta = deltas.sorted()[deltas.size / 2]
        val intervals = DoubleArray(deltas.size) { (deltas[it] / medianDelta).coerceIn(.35, 3.0) }
        val anchorTarget = anchorValue?.div(limit)
        repeat(6) {
            val robust = DoubleArray(source.size - 2)
            for (i in 1 until source.lastIndex) {
                val acceleration = (result[i + 1] - result[i]) / intervals[i] -
                    (result[i] - result[i - 1]) / intervals[i - 1]
                robust[i - 1] = 9.0 / max(.045, abs(acceleration))
            }
            val right = DoubleArray(source.size) { confidence[it] * normalized[it] }
            if (anchor != null && anchorTarget != null) right[anchor] += 90.0 * anchorTarget

            fun apply(vector: DoubleArray): DoubleArray {
                val output = DoubleArray(vector.size) { confidence[it] * vector[it] }
                if (anchor != null && anchorTarget != null) output[anchor] += 90.0 * vector[anchor]
                for (i in 1 until vector.lastIndex) {
                    val c0 = 1.0 / intervals[i - 1]
                    val c1 = -(1.0 / intervals[i - 1] + 1.0 / intervals[i])
                    val c2 = 1.0 / intervals[i]
                    val value = (c0 * vector[i - 1] + c1 * vector[i] + c2 * vector[i + 1]) * robust[i - 1]
                    output[i - 1] += c0 * value
                    output[i] += c1 * value
                    output[i + 1] += c2 * value
                }
                return output
            }

            val solved = conjugateGradient(result, right, ::apply)
            for (i in result.indices) {
                result[i] = solved[i].coerceIn(normalized[i] - 1.25, normalized[i] + 1.25)
            }
            if (anchor != null && anchorTarget != null) result[anchor] = anchorTarget
        }
        return DoubleArray(result.size) { result[it] * limit }
    }

    private fun conjugateGradient(
        initial: DoubleArray,
        right: DoubleArray,
        apply: (DoubleArray) -> DoubleArray,
    ): DoubleArray {
        val x = initial.copyOf()
        val applied = apply(x)
        val residual = DoubleArray(x.size) { right[it] - applied[it] }
        val direction = residual.copyOf()
        var residualNorm = dot(residual, residual)
        repeat(80) {
            if (residualNorm < 1e-10) return x
            val multiplied = apply(direction)
            val denominator = dot(direction, multiplied)
            if (abs(denominator) < 1e-12) return x
            val amount = residualNorm / denominator
            for (i in x.indices) {
                x[i] += amount * direction[i]
                residual[i] -= amount * multiplied[i]
            }
            val nextNorm = dot(residual, residual)
            val beta = nextNorm / residualNorm
            for (i in direction.indices) direction[i] = residual[i] + beta * direction[i]
            residualNorm = nextNorm
        }
        return x
    }

    private fun dot(first: DoubleArray, second: DoubleArray): Double =
        first.indices.sumOf { first[it] * second[it] }

    private fun interpolate(first: Shift, second: Shift, amount: Float, confidence: Float): Shift {
        val t = amount.coerceIn(0f, 1f)
        val angle = first.rotationRadians + shortestAngle(first.rotationRadians, second.rotationRadians) * t
        val logScale = ln(first.scale.toDouble()) * (1f - t) + ln(second.scale.toDouble()) * t
        return Shift(
            first.x + (second.x - first.x) * t,
            first.y + (second.y - first.y) * t,
            angle,
            exp(logScale).toFloat(),
            confidence,
            first.stretch + (second.stretch - first.stretch) * t,
            first.shear + (second.shear - first.shear) * t,
            first.residual + (second.residual - first.residual) * t,
            if (t < .5f) first.model else second.model,
        )
    }

    private fun shortestAngle(from: Float, to: Float): Float {
        var difference = to - from
        while (difference > Math.PI) difference -= (Math.PI * 2).toFloat()
        while (difference < -Math.PI) difference += (Math.PI * 2).toFloat()
        return difference
    }

    private fun unwrap(previous: Double, current: Double): Double {
        var value = current
        while (value - previous > Math.PI) value -= Math.PI * 2
        while (value - previous < -Math.PI) value += Math.PI * 2
        return value
    }

    private fun sample(image: GrayImage, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, image.width - 1)
        val y0 = y.toInt().coerceIn(0, image.height - 1)
        val x1 = min(x0 + 1, image.width - 1)
        val y1 = min(y0 + 1, image.height - 1)
        val fx = x - x0
        val fy = y - y0
        val top = image.values[y0 * image.width + x0] * (1f - fx) + image.values[y0 * image.width + x1] * fx
        val bottom = image.values[y1 * image.width + x0] * (1f - fx) + image.values[y1 * image.width + x1] * fx
        return top * (1f - fy) + bottom * fy
    }

    private fun inside(image: GrayImage, x: Float, y: Float, margin: Float): Boolean =
        x >= margin && y >= margin && x < image.width - margin && y < image.height - margin

    private fun squaredDistance(first: Point, second: Point): Float {
        val x = first.x - second.x
        val y = first.y - second.y
        return x * x + y * y
    }
}
