package chimahon.ocr

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.system.measureTimeMillis

fun main() {
    val testDir = File("G:/Downloads/test")
    val images = testDir.listFiles()?.filter { it.extension == "webp" }?.sorted() ?: run {
        println("No webp files found in G:/Downloads/test")
        return
    }

    println("=== CHUNKING BENCHMARK ===")
    println("Images: ${images.map { it.name }}\n")

    for (imageFile in images) {
        val img = ImageIO.read(imageFile) ?: continue
        val width = img.width
        val height = img.height

        println("--- ${imageFile.name} (${width}x${height}) ---")

        var varianceCuts = listOf<Int>()
        val varianceTime = measureTimeMillis {
            varianceCuts = findCutsVariance(img, width, height)
        }

        var edgeCuts = listOf<Int>()
        val edgeTime = measureTimeMillis {
            edgeCuts = findCutsEdge(img, width, height)
        }

        println("  Variance: ${varianceCuts.size} cuts in ${varianceTime}ms -> ${varianceCuts.joinToString()}")
        println("  Edge:     ${edgeCuts.size} cuts in ${edgeTime}ms -> ${edgeCuts.joinToString()}")

        for (cut in varianceCuts) {
            val v = computeRowVarianceForImage(img, width, cut)
            println("    Variance cut@$cut: row_variance=$v")
        }
        for (cut in edgeCuts) {
            val e = computeEdgeScoreForImage(img, width, cut)
            println("    Edge cut@$cut: edge_score=$e")
        }
        println()
    }
}

const val CHUNK_HEIGHT_LIMIT = 3000
const val CHUNK_BOUNDARY_SEARCH_RANGE = 600
const val CHUNK_BOUNDARY_LINE_MIN_HEIGHT = 5
const val LOW_VARIANCE_THRESHOLD = 150L
const val EDGE_THRESHOLD = 30

fun findCutsVariance(img: BufferedImage, width: Int, height: Int): List<Int> {
    val cuts = mutableListOf<Int>()
    var currentY = 0
    while (currentY < height) {
        val remainingHeight = height - currentY
        val targetChunkHeight = min(CHUNK_HEIGHT_LIMIT, remainingHeight)
        if (targetChunkHeight == 0) break

        if (remainingHeight <= CHUNK_HEIGHT_LIMIT) {
            if (remainingHeight > 96 * 2) cuts += currentY
            break
        }

        val targetY = currentY + targetChunkHeight
        val searchStart = max(0, targetY - CHUNK_BOUNDARY_SEARCH_RANGE)
        val searchEnd = min(height - CHUNK_BOUNDARY_LINE_MIN_HEIGHT, targetY + CHUNK_BOUNDARY_SEARCH_RANGE)

        var bestY: Int? = null
        var bestScore = Int.MAX_VALUE

        for (y in searchStart..searchEnd - CHUNK_BOUNDARY_LINE_MIN_HEIGHT + 1) {
            val rowVariance = computeRowVarianceForImage(img, width, y)
            if (rowVariance < LOW_VARIANCE_THRESHOLD) {
                var consecutive = 1
                for (cy in y + 1 until min(y + CHUNK_BOUNDARY_LINE_MIN_HEIGHT, searchEnd + 1)) {
                    if (computeRowVarianceForImage(img, width, cy) >= LOW_VARIANCE_THRESHOLD) break
                    consecutive++
                }
                if (consecutive >= CHUNK_BOUNDARY_LINE_MIN_HEIGHT) {
                    val dist = abs(y - targetY)
                    if (dist < bestScore) {
                        bestScore = dist
                        bestY = y
                    }
                }
            }
        }

        val actualHeight = if (bestY != null) bestY - currentY else targetChunkHeight
        cuts += currentY
        currentY += actualHeight - 96
    }
    return cuts
}

fun computeRowVarianceForImage(img: BufferedImage, width: Int, y: Int): Long {
    var sumR = 0L; var sumG = 0L; var sumB = 0L
    for (x in 0 until width) {
        val rgb = img.getRGB(x, y)
        sumR += (rgb shr 16) and 0xFF
        sumG += (rgb shr 8) and 0xFF
        sumB += rgb and 0xFF
    }
    val meanR = sumR / width
    val meanG = sumG / width
    val meanB = sumB / width

    var variance = 0L
    for (x in 0 until width) {
        val rgb = img.getRGB(x, y)
        val dr = ((rgb shr 16) and 0xFF) - meanR
        val dg = ((rgb shr 8) and 0xFF) - meanG
        val db = (rgb and 0xFF) - meanB
        variance += dr * dr + dg * dg + db * db
    }
    return variance / width
}

fun findCutsEdge(img: BufferedImage, width: Int, height: Int): List<Int> {
    val edgeScores = IntArray(height)
    for (y in 0 until height) {
        edgeScores[y] = computeEdgeScoreForImage(img, width, y)
    }

    val cuts = mutableListOf<Int>()
    var currentY = 0
    while (currentY < height) {
        val remainingHeight = height - currentY
        val targetChunkHeight = min(CHUNK_HEIGHT_LIMIT, remainingHeight)
        if (targetChunkHeight == 0) break

        if (remainingHeight <= CHUNK_HEIGHT_LIMIT) {
            if (remainingHeight > 96 * 2) cuts += currentY
            break
        }

        val targetY = currentY + targetChunkHeight
        val searchStart = max(0, targetY - CHUNK_BOUNDARY_SEARCH_RANGE)
        val searchEnd = min(height - CHUNK_BOUNDARY_LINE_MIN_HEIGHT, targetY + CHUNK_BOUNDARY_SEARCH_RANGE)

        var bestY: Int? = null
        var bestScore = Int.MAX_VALUE

        for (y in searchStart..searchEnd - CHUNK_BOUNDARY_LINE_MIN_HEIGHT + 1) {
            var allLow = true
            for (dy in 0 until CHUNK_BOUNDARY_LINE_MIN_HEIGHT) {
                if (edgeScores[y + dy] >= EDGE_THRESHOLD) {
                    allLow = false
                    break
                }
            }
            if (allLow) {
                val dist = abs(y - targetY)
                if (dist < bestScore) {
                    bestScore = dist
                    bestY = y
                }
            }
        }

        val actualHeight = if (bestY != null) bestY - currentY else targetChunkHeight
        cuts += currentY
        currentY += actualHeight - 96
    }
    return cuts
}

fun computeEdgeScoreForImage(img: BufferedImage, width: Int, y: Int): Int {
    if (width < 2) return 0
    var edgeSum = 0
    for (x in 1 until width) {
        val rgb1 = img.getRGB(x - 1, y)
        val rgb2 = img.getRGB(x, y)
        val dr = abs(((rgb1 shr 16) and 0xFF) - ((rgb2 shr 16) and 0xFF))
        val dg = abs(((rgb1 shr 8) and 0xFF) - ((rgb2 shr 8) and 0xFF))
        val db = abs((rgb1 and 0xFF) - (rgb2 and 0xFF))
        edgeSum += dr + dg + db
    }
    return edgeSum / width
}
