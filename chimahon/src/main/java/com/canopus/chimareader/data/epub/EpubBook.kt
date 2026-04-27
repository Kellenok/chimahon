package com.canopus.chimareader.data.epub

import java.io.File

private fun decodeHtmlEntities(html: String): String {
    var text = html
    // Common numeric entities (&#039; &#39; etc) - handles &#39; as well
    val numericRegex = Regex("&#([0-9]+);")
    text = numericRegex.replace(text) { match ->
        val code = match.groupValues[1].toIntOrNull()
        if (code != null && code in 0..0x10FFFF) code.toInt().toChar().toString() else match.value
    }
    // Common named entities - chain all for efficiency
    return text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&hellip;", "…")
        .replace("&lsquo;", "'")
        .replace("&rsquo;", "'")
        .replace("&ldquo;", """)
        .replace("&rdquo;", """)
        .replace("&middot;", "·")
        .replace("&bull;", "•")
        .replace("&deg;", "°")
        .replace("&ensp;", " ")
        .replace("&emsp;", " ")
        .replace("&zwsp;", "")
        .replace("&shy;", "")
}

data class EpubBook(
    val title: String? = null,
    val author: String? = null,
    val language: String? = null,
    val coverPath: String? = null,
    val metadata: EpubMetadata = EpubMetadata(),
    val manifest: EpubManifest = EpubManifest(),
    val spine: EpubSpine = EpubSpine(),
    val tableOfContents: List<TocEntry> = emptyList(),
    val contentDirectory: String = "",
    val zipPath: String = "",
    val extractedDir: File? = null,
) {
    val coverHref: String?
        get() {
            val coverId = metadata.coverId ?: return null
            val manifestItem = manifest.items[coverId] ?: return null
            // Prepend content directory if present
            return if (contentDirectory.isNotEmpty()) {
                "$contentDirectory${manifestItem.href}"
            } else {
                manifestItem.href
            }
        }

    val linearSpineItems: List<SpineItem>
        get() = spine.items.filter { it.linear }

    fun getChapterHref(index: Int): String? {
        val spineItem = linearSpineItems.getOrNull(index) ?: return null
        val manifestItem = manifest.items[spineItem.idref] ?: return null
        // Prepend content directory if present (e.g., "item/xhtml/p-001.xhtml")
        return if (contentDirectory.isNotEmpty()) {
            "$contentDirectory${manifestItem.href}"
        } else {
            manifestItem.href
        }
    }

    // Hoshi Shims
    fun title(): String? = title
    fun spine(): EpubSpine = spine
    fun chapterAbsolutePath(index: UInt): String? {
        val href = getChapterHref(index.toInt()) ?: return null
        val baseDir = extractedDir ?: return null
        return File(baseDir, href).absolutePath
    }

    /**
     * Returns the cached image URL for an image-only spine item.
     * The URL was resolved once during book parsing and stored in [SpineItem.imageUrl].
     */
    fun getImageUrl(index: Int): String? =
        linearSpineItems.getOrNull(index)
            ?.takeIf { it.type == SpineItemType.IMAGE_ONLY }
            ?.imageUrl

    // Cache to prevent recalculating Chapter Character length
    private val chapterLengthCache = mutableMapOf<Int, Int>()

    /**
     * Calculates the exploredCharCount of a specific chapter matching Hoshi-Reader logic
     */
    fun getChapterCharacters(index: Int): Int {
        if (chapterLengthCache.containsKey(index)) return chapterLengthCache[index]!!
        
        val spineType = linearSpineItems.getOrNull(index)?.type
        if (spineType == SpineItemType.IMAGE_ONLY) {
            chapterLengthCache[index] = 0
            return 0
        }

        val path = chapterAbsolutePath(index.toUInt()) ?: return 0
        val file = File(path)
        if (!file.exists()) return 0

        return try {
            var html = file.readText()

            // Extract body content only
            val bodyRegex = Regex("(?s)<body[^>]*>(.*)</body>")
            val bodyMatch = bodyRegex.find(html)
            html = bodyMatch?.groupValues?.getOrNull(1) ?: html

            // Remove rt tags (ruby reading)
            html = html.replace(Regex("(?s)<rt>.*?</rt>"), "")

            // Remove script and style tags with content
            html = html.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), "")

            // Remove all HTML tags
            html = html.replace(Regex("<[^>]+>"), "")

            // Decode common HTML entities efficiently (single regex pass)
            val text = decodeHtmlEntities(html)

            // Filter to Japanese/Chinese characters, digits and alphabet (TTU method - uppercase only)
            val hoshiRegex = Regex("[^0-9A-Z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚｦ-ﾝ\\p{Radical}\\p{Unified_Ideograph}]")
            val filteredText = text.replace(hoshiRegex, "")

            // Use Array.from equivalent (codepoints) like TTU
            val charCount = filteredText.toList().size
            chapterLengthCache[index] = charCount
            charCount
        } catch(e: Exception) {
            0
        }
    }

    /**
     * Converts an absolute character count (from TTSU) back into a 
     * specific chapter index and decimal progress (0.0 to 1.0) for that chapter.
     */
    fun convertCharsToProgress(totalExploredChars: Int): Pair<Int, Double> {
        var unallocatedChars = totalExploredChars
        for (i in 0 until linearSpineItems.size) {
            val chapterChars = getChapterCharacters(i)
            if (unallocatedChars <= chapterChars) {
                // If chapter has 0 chars (e.g. image), prevent NaN/Infinity
                val progress = if (chapterChars == 0) 0.0 else unallocatedChars.toDouble() / chapterChars.toDouble()
                return Pair(i, progress.coerceIn(0.0, 1.0))
            }
            unallocatedChars -= chapterChars
        }
        // If chars exceed total book size, clamp to the last chapter at 100%
        return Pair(maxOf(0, linearSpineItems.size - 1), 1.0)
    }
}