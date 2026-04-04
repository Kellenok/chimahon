package chimahon.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "LensImageProcessor"

internal data class ImageChunk(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val globalY: Int,
    val fullWidth: Int,
    val fullHeight: Int,
)

internal data class ProcessedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

internal fun processImageFromBytes(data: ByteArray): ProcessedImage {
    val bitmap = decodeBitmap(data)
    return processImageInternal(bitmap)
}

internal fun splitImageIntoChunks(
    data: ByteArray,
    chunkHeightLimit: Int = 3000,
): List<ImageChunk> {
    val bitmap = decodeBitmap(data)
    val fullWidth = bitmap.width
    val fullHeight = bitmap.height
    val chunks = mutableListOf<ImageChunk>()

    try {
        var currentY = 0
        while (currentY < fullHeight) {
            val targetChunkHeight = min(chunkHeightLimit, fullHeight - currentY)
            if (targetChunkHeight == 0) break

            // Try to find a clean cut line near the target height
            val actualChunkHeight = if (currentY + targetChunkHeight < fullHeight) {
                findCleanCutLine(bitmap, currentY + targetChunkHeight, fullWidth, fullHeight)
                    ?: targetChunkHeight
            } else {
                targetChunkHeight
            }

            val chunkBitmap = Bitmap.createBitmap(bitmap, 0, currentY, fullWidth, actualChunkHeight)
            try {
                chunks += ImageChunk(
                    pngBytes = bitmapToPng(chunkBitmap),
                    width = fullWidth,
                    height = actualChunkHeight,
                    globalY = currentY,
                    fullWidth = fullWidth,
                    fullHeight = fullHeight,
                )
            } finally {
                chunkBitmap.recycle()
            }

            currentY += actualChunkHeight
        }
    } finally {
        bitmap.recycle()
    }

    return chunks
}

private fun decodeBitmap(data: ByteArray): Bitmap {
    return BitmapFactory.decodeByteArray(data, 0, data.size)
        ?: error("Failed to decode image from bytes")
}

private fun processImageInternal(bitmap: Bitmap): ProcessedImage {
    val resized = resizeIfNeeded(bitmap)
    val pngBytes = bitmapToPng(resized)
    if (resized !== bitmap) bitmap.recycle()
    return ProcessedImage(bytes = pngBytes, width = resized.width, height = resized.height)
}

private fun bitmapToPng(bitmap: Bitmap): ByteArray {
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}

/**
 * Dual-strategy image preparation:
 * - Regular pages: resize proportional to 3MP (owocr approach, full-page context)
 * - Very tall or extreme strips: chunk at native resolution in 3000px bands
 *
 * Returns a Sequence to process chunks lazily — only one chunk PNG exists in memory at a time.
 */
internal fun prepareForOcr(data: ByteArray): Sequence<ImageChunk> {
    val bitmap = decodeBitmap(data)
    val w = bitmap.width
    val h = bitmap.height
    val pixelCount = w * h
    val aspectRatio = h.toDouble() / w
    val shouldChunkByHeight = h > TALL_IMAGE_CHUNK_THRESHOLD
    val shouldChunkByAspect = aspectRatio > WEBTOON_ASPECT_RATIO && pixelCount > MAX_TOTAL_PIXELS

    return sequence {
        try {
            if (shouldChunkByHeight || shouldChunkByAspect) {
                Log.d(TAG, "prepareForOcr: chunking triggered (h=$h, aspect=$aspectRatio, px=$pixelCount)")
                yieldAll(chunkImageSequence(bitmap))
            } else {
                Log.d(TAG, "prepareForOcr: single chunk (h=$h, aspect=$aspectRatio, px=$pixelCount)")
                val resized = resizeToMaxPixels(bitmap, MAX_TOTAL_PIXELS)
                yield(
                    ImageChunk(
                        pngBytes = bitmapToPng(resized),
                        width = resized.width,
                        height = resized.height,
                        globalY = 0,
                        fullWidth = resized.width,
                        fullHeight = resized.height,
                    ),
                )
            }
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * Resize bitmap proportionally to fit within maxTotalPixels.
 * Equivalent to owocr's GoogleLens._preprocess: sqrt(maxPixels * aspectRatio) for width.
 */
internal fun resizeToMaxPixels(bitmap: Bitmap, maxTotalPixels: Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixelCount = w * h

    if (pixelCount <= maxTotalPixels) return bitmap

    val aspectRatio = w.toDouble() / h
    val newW = sqrt(maxTotalPixels * aspectRatio).toInt().coerceAtLeast(1)
    val newH = (newW / aspectRatio).toInt().coerceAtLeast(1)

    return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
}

/**
 * Chunk image into vertical strips at native resolution.
 * Finds clean horizontal separator lines near chunk boundaries instead of blindly cutting at fixed positions.
 */
internal fun chunkImage(
    bitmap: Bitmap,
    chunkHeightLimit: Int = CHUNK_HEIGHT_LIMIT,
    chunkOverlapPx: Int = CHUNK_OVERLAP_PX,
): List<ImageChunk> {
    val fullWidth = bitmap.width
    val fullHeight = bitmap.height
    val chunks = mutableListOf<ImageChunk>()

    try {
        var currentY = 0
        while (currentY < fullHeight) {
            val remainingHeight = fullHeight - currentY
            val targetChunkHeight = min(chunkHeightLimit, remainingHeight)
            if (targetChunkHeight == 0) break

            if (remainingHeight <= chunkHeightLimit) {
                if (remainingHeight > chunkOverlapPx * 2) {
                    val chunkBitmap = Bitmap.createBitmap(bitmap, 0, currentY, fullWidth, remainingHeight)
                    try {
                        chunks += ImageChunk(
                            pngBytes = bitmapToPng(chunkBitmap),
                            width = fullWidth,
                            height = remainingHeight,
                            globalY = currentY,
                            fullWidth = fullWidth,
                            fullHeight = fullHeight,
                        )
                    } finally {
                        chunkBitmap.recycle()
                    }
                }
                break
            }

            val actualChunkHeight = run {
                val cleanY = findCleanCutLine(bitmap, currentY + targetChunkHeight, fullWidth, fullHeight)
                if (cleanY != null) {
                    val relativeY = cleanY - currentY
                    Log.d(TAG, "chunkImage: clean cut found at targetY=${currentY + targetChunkHeight}, cleanY=$cleanY, actualChunkHeight=$relativeY")
                    relativeY
                } else {
                    Log.d(TAG, "chunkImage: no clean cut near ${currentY + targetChunkHeight}, using exact $targetChunkHeight")
                    targetChunkHeight
                }
            }

            val chunkBitmap = Bitmap.createBitmap(bitmap, 0, currentY, fullWidth, actualChunkHeight)
            try {
                chunks += ImageChunk(
                    pngBytes = bitmapToPng(chunkBitmap),
                    width = fullWidth,
                    height = actualChunkHeight,
                    globalY = currentY,
                    fullWidth = fullWidth,
                    fullHeight = fullHeight,
                )
            } finally {
                chunkBitmap.recycle()
            }
            currentY += actualChunkHeight - chunkOverlapPx
        }
    } finally {
        bitmap.recycle()
    }

    Log.d(TAG, "chunkImage: produced ${chunks.size} chunks for ${fullWidth}x${fullHeight} image")
    return chunks
}

/**
 * Chunk image lazily — yields one chunk at a time to avoid holding all PNGs in memory.
 */
internal fun chunkImageSequence(
    bitmap: Bitmap,
    chunkHeightLimit: Int = CHUNK_HEIGHT_LIMIT,
    chunkOverlapPx: Int = CHUNK_OVERLAP_PX,
): Sequence<ImageChunk> = sequence {
    val fullWidth = bitmap.width
    val fullHeight = bitmap.height

    var currentY = 0
    while (currentY < fullHeight) {
        val remainingHeight = fullHeight - currentY
        val targetChunkHeight = min(chunkHeightLimit, remainingHeight)
        if (targetChunkHeight == 0) break

        // If remaining fits in one chunk, take it all and exit
        if (remainingHeight <= chunkHeightLimit) {
            // Skip if already covered by previous chunk's overlap or too small to be useful
            if (remainingHeight > chunkOverlapPx * 2) {
                val chunkBitmap = Bitmap.createBitmap(bitmap, 0, currentY, fullWidth, remainingHeight)
                try {
                    yield(
                        ImageChunk(
                            pngBytes = bitmapToPng(chunkBitmap),
                            width = fullWidth,
                            height = remainingHeight,
                            globalY = currentY,
                            fullWidth = fullWidth,
                            fullHeight = fullHeight,
                        ),
                    )
                } finally {
                    chunkBitmap.recycle()
                }
            }
            break
        }

        val actualChunkHeight = run {
            val cleanY = findCleanCutLine(bitmap, currentY + targetChunkHeight, fullWidth, fullHeight)
            if (cleanY != null) {
                val relativeY = cleanY - currentY
                Log.d(TAG, "chunkImageSequence: clean cut found at targetY=${currentY + targetChunkHeight}, cleanY=$cleanY, actualChunkHeight=$relativeY")
                relativeY
            } else {
                Log.d(TAG, "chunkImageSequence: no clean cut near ${currentY + targetChunkHeight}, using exact $targetChunkHeight")
                targetChunkHeight
            }
        }

        val chunkBitmap = Bitmap.createBitmap(bitmap, 0, currentY, fullWidth, actualChunkHeight)
        try {
            yield(
                ImageChunk(
                    pngBytes = bitmapToPng(chunkBitmap),
                    width = fullWidth,
                    height = actualChunkHeight,
                    globalY = currentY,
                    fullWidth = fullWidth,
                    fullHeight = fullHeight,
                ),
            )
        } finally {
            chunkBitmap.recycle()
        }
        currentY += actualChunkHeight - chunkOverlapPx
    }
}

/**
 * Search for a clean horizontal separator line near the target Y position.
 * Looks ±CHUNK_BOUNDARY_SEARCH_RANGE pixels for a run of CHUNK_BOUNDARY_LINE_MIN_HEIGHT
 * consecutive rows with very low color variance (uniform margin / separator band).
 * Returns the best cut Y relative to the bitmap start, or null if not found.
 */
private fun findCleanCutLine(
    bitmap: Bitmap,
    targetY: Int,
    width: Int,
    fullHeight: Int,
): Int? {
    val searchStart = (targetY - CHUNK_BOUNDARY_SEARCH_RANGE).coerceAtLeast(0)
    val searchEnd = (targetY + CHUNK_BOUNDARY_SEARCH_RANGE).coerceAtMost(fullHeight - CHUNK_BOUNDARY_LINE_MIN_HEIGHT)
    val minLineHeight = CHUNK_BOUNDARY_LINE_MIN_HEIGHT

    val pixels = IntArray(width)
    var bestY: Int? = null
    var bestScore = Int.MAX_VALUE

    var y = searchStart
    while (y <= searchEnd - minLineHeight + 1) {
        bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
        val rowVariance = computeRowVariance(pixels, width)

        if (rowVariance < LOW_VARIANCE_THRESHOLD) {
            var consecutiveCount = 1
            var checkY = y + 1
            while (checkY < y + minLineHeight && checkY <= searchEnd) {
                bitmap.getPixels(pixels, 0, width, 0, checkY, width, 1)
                if (computeRowVariance(pixels, width) >= LOW_VARIANCE_THRESHOLD) break
                consecutiveCount++
                checkY++
            }

            if (consecutiveCount >= minLineHeight) {
                val distance = kotlin.math.abs(y - targetY)
                if (distance < bestScore) {
                    bestScore = distance
                    bestY = y
                }
                y += consecutiveCount
                continue
            }
        }
        y++
    }

    return bestY
}

/**
 * Compute average squared distance from mean color for a row.
 * Low values = uniform row (white margin, black separator line, etc.)
 */
private fun computeRowVariance(pixels: IntArray, width: Int): Long {
    var sumR = 0L; var sumG = 0L; var sumB = 0L
    for (i in 0 until width) {
        sumR += (pixels[i] shr 16) and 0xFF
        sumG += (pixels[i] shr 8) and 0xFF
        sumB += pixels[i] and 0xFF
    }
    val meanR = sumR / width
    val meanG = sumG / width
    val meanB = sumB / width

    var variance = 0L
    for (i in 0 until width) {
        val dr = ((pixels[i] shr 16) and 0xFF) - meanR
        val dg = ((pixels[i] shr 8) and 0xFF) - meanG
        val db = (pixels[i] and 0xFF) - meanB
        variance += dr * dr + dg * dg + db * db
    }
    return variance / width
}

private fun resizeIfNeeded(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= IMAGE_MAX_DIMENSION && h <= IMAGE_MAX_DIMENSION) return bitmap
    val scale = min(IMAGE_MAX_DIMENSION.toFloat() / w, IMAGE_MAX_DIMENSION.toFloat() / h)
    val newW = (w * scale).toInt().coerceAtLeast(1)
    val newH = (h * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
}
