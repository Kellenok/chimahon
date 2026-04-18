package com.canopus.chimareader.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.canopus.chimareader.data.epub.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

data class ImportResult(
    val metadata: BookMetadata? = null,
    val error: String? = null
)

object BookImporter {

    private const val TAG = "BookImporter"

    suspend fun importEpub(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting import from URI: $uri")
            
            // Copy to temp file
            val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.epub")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext ImportResult(error = "Could not read file")

            Log.d(TAG, "Copied to temp: ${tempFile.absolutePath}, size: ${tempFile.length()}")

            // Validate it's a valid zip
            try {
                ZipFile(tempFile).use { zip ->
                    val entries = zip.entries().asSequence().toList()
                    Log.d(TAG, "ZIP contains ${entries.size} entries")
                    if (entries.isEmpty()) {
                        return@withContext ImportResult(error = "File is empty")
                    }
                }
            } catch (e: Exception) {
                tempFile.delete()
                return@withContext ImportResult(error = "Not a valid EPUB file: ${e.message}")
            }

            val bookId = UUID.randomUUID()
            val booksDir = BookStorage.getBooksDirectory(context)
            Log.d(TAG, "Books directory: ${booksDir.absolutePath}")
            booksDir.mkdirs()
            
            val bookDir = File(booksDir, bookId.toString())
            Log.d(TAG, "Book directory: ${bookDir.absolutePath}")
            bookDir.mkdirs()

            // Extract ZIP
            ZipFile(tempFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val file = File(bookDir, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            tempFile.delete()

            Log.d(TAG, "Extracted to: ${bookDir.absolutePath}")
            
            // Debug: list extracted files
            val extractedFiles = bookDir.walkTopDown().take(20).map { it.relativeTo(bookDir).path }.toList()
            Log.d(TAG, "Extracted files (first 20): $extractedFiles")

            // Parse the EPUB
            val extractedBook = EpubParser.parse(bookDir)
            // Use coverHref which now includes contentDirectory
            val coverAbsPath = extractedBook.coverHref?.let { File(bookDir, it).absolutePath }
            val title = extractedBook.title ?: "Unknown"

            Log.d(TAG, "Parsed EPUB: title=$title, contentDir=${extractedBook.contentDirectory}, chapters=${extractedBook.spine.items.size}")
            
            // Log first few chapter hrefs
            for (i in 0 until minOf(3, extractedBook.linearSpineItems.size)) {
                val href = extractedBook.getChapterHref(i)
                Log.d(TAG, "Chapter $i href: $href")
            }

            val metadata = BookMetadata(
                id = bookId.toString(),
                title = title,
                cover = coverAbsPath,
                folder = bookId.toString(),
                lastAccess = System.currentTimeMillis()
            )
            BookStorage.saveMetadata(metadata, bookDir)
            BookStorage.saveSpineCache(extractedBook.spine, bookDir)

            return@withContext ImportResult(metadata = metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            return@withContext ImportResult(error = "Import failed: ${e.message}")
        }
    }
}
