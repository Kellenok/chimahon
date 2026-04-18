package com.canopus.chimareader.data.epub

import android.util.Log
import java.io.File

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
}