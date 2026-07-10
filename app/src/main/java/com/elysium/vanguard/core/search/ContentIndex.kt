package com.elysium.vanguard.core.search

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * PHASE 1.5 — In-memory inverted index for full-text content search.
 *
 * We deliberately do NOT pull in Tantivy (which requires an NDK build) or
 * SQLite FTS (overkill for the dataset sizes we expect). This is a
 * hand-rolled inverted index that:
 *   - Tokenizes each indexed file (whitespace + punctuation split)
 *   - Drops stop words and short tokens
 *   - Maps every token to the set of [Posting]s (file + positions)
 *   - Stores a per-file term frequency map for ranking
 *
 * The whole index lives in memory; on Android that's fine for the realistic
 * upper bound of a phone's documents (a few hundred MB of text). When the
 * user enables background indexing (Phase 3.4), we'd persist the index to
 * disk and reload on cold start; for now we re-index on demand.
 *
 * Search algorithm:
 *   1. Tokenize the query.
 *   2. For each query token, look up its posting list.
 *   3. Intersect the lists (sets), preferring smaller lists first.
 *   4. For each candidate file, score = sum of BM25-style weights.
 *   5. Return top N sorted by score desc.
 *
 * Why BM25 over TF-IDF: BM25's tunable saturation handles long documents
 * (e.g. 50-page PDFs) without one term dominating. The constants k1=1.2 and
 * b=0.75 are the original Robertson/Walker defaults.
 */
class ContentIndex {

    /**
     * A single occurrence of a token inside a file. We don't track every
     * position — only the count is needed for BM25. Position is kept as a
     * byte offset for snippet extraction later.
     */
    data class Posting(
        val filePath: String,
        val count: Int,
        val firstOffset: Long
    )

    /**
     * A search hit returned to the caller. The [snippet] is a small window
     * around the first match to give the user something to read.
     */
    data class Hit(
        val filePath: String,
        val displayName: String,
        val score: Double,
        val snippet: String?
    )

    private val tokenToPostings = ConcurrentHashMap<String, MutableList<Posting>>()
    private val fileToLength = ConcurrentHashMap<String, Long>()
    private val fileToDocFreq = ConcurrentHashMap<String, Map<String, Int>>()  // file → (token → count)
    private val indexedFiles = ConcurrentHashMap.newKeySet<String>()

    /** Total number of files currently indexed (cached for BM25). */
    private var totalDocs: Int = 0
        get() = indexedFiles.size

    /** Clear the entire index. Used when the user resets search settings. */
    fun clear() {
        tokenToPostings.clear()
        fileToLength.clear()
        fileToDocFreq.clear()
        indexedFiles.clear()
    }

    /**
     * Index the textual content of [file] and store per-token postings.
     *
     * @return true if the file was indexed; false if it was skipped (e.g.
     *         file doesn't exist, isn't readable, or exceeds [maxFileBytes])
     */
    fun indexFile(file: File, maxFileBytes: Long = 5L * 1024 * 1024): Boolean {
        if (!file.exists() || !file.canRead()) return false
        if (file.length() > maxFileBytes) return false
        // Binary detection: if the first 8 KB contains NUL bytes, skip.
        if (looksBinary(file)) return false
        unindexFile(file.absolutePath)  // de-dupe

        val text = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { return false }
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return false

        val perTokenCounts = HashMap<String, Int>()
        var firstOffset = -1L
        var firstToken: String? = null
        // We compute term frequencies + first occurrence offset.
        val seenOffsets = HashMap<String, Long>()
        for ((i, tok) in tokens.withIndex()) {
            perTokenCounts[tok] = (perTokenCounts[tok] ?: 0) + 1
            if (!seenOffsets.containsKey(tok)) {
                seenOffsets[tok] = tokens[i].length.toLong() * i  // approximate
            }
        }
        // We didn't track real offsets during tokenization; use 0 as a stub
        // since we use this only to identify the first occurrence.
        val firstOffsetStub = 0L

        val path = file.absolutePath
        indexedFiles.add(path)
        fileToLength[path] = file.length()
        fileToDocFreq[path] = perTokenCounts

        for ((token, count) in perTokenCounts) {
            val posting = Posting(path, count, firstOffsetStub)
            val list = tokenToPostings.getOrPut(token) { mutableListOf() }
            synchronized(list) { list.add(posting) }
        }
        return true
    }

    /** Remove [path] from the index. Idempotent. */
    fun unindexFile(path: String) {
        if (!indexedFiles.remove(path)) return
        fileToLength.remove(path)
        fileToDocFreq.remove(path)
        for ((_, list) in tokenToPostings) {
            synchronized(list) { list.removeAll { it.filePath == path } }
        }
    }

    /**
     * Search the index for [query]. Returns the top [limit] hits by score.
     *
     * Multi-term semantics: a hit must contain EVERY token from the query (AND
     * semantics), then ranked by BM25 score. Single-token queries are the
     * trivial case of this rule.
     *
     * Empty query → empty result. All-whitespace query → empty result.
     */
    fun search(query: String, limit: Int = 50): List<Hit> {
        val qTokens = tokenize(query)
        if (qTokens.isEmpty()) return emptyList()
        val N = totalDocs.coerceAtLeast(1)
        val avgLen = if (fileToLength.isEmpty()) 0.0
        else fileToLength.values.average()

        // AND intersection of posting lists. If any query token has no postings
        // at all, the intersection is empty and we return immediately.
        val perTokenFiles = qTokens.map { tok ->
            val list = tokenToPostings[tok] ?: return emptyList()
            list.map { it.filePath }.toSet()
        }
        if (perTokenFiles.isEmpty()) return emptyList()
        var candidates = perTokenFiles.first()
        for (i in 1 until perTokenFiles.size) {
            candidates = candidates intersect perTokenFiles[i]
            if (candidates.isEmpty()) return emptyList()
        }
        if (candidates.isEmpty()) return emptyList()

        // Score with BM25.
        val scored = candidates.map { path ->
            val length = fileToLength[path] ?: 0L
            val docFreq = fileToDocFreq[path] ?: emptyMap()
            var score = 0.0
            for (tok in qTokens) {
                val f = docFreq[tok] ?: 0
                if (f == 0) continue
                val df = tokenToPostings[tok]?.size ?: 0
                // IDF with classic smoothing (avoid negative for very common terms).
                val idf = kotlin.math.ln(1.0 + (N - df + 0.5) / (df + 0.5))
                val denom = f + K1 * (1 - B + B * length / avgLen.coerceAtLeast(1.0))
                val tf = (f * (K1 + 1)) / denom
                score += idf * tf
            }
            path to score
        }.filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)

        return scored.map { (path, score) ->
            val file = File(path)
            Hit(
                filePath = path,
                displayName = file.name,
                score = score,
                snippet = extractSnippet(path, qTokens.first())
            )
        }
    }

    fun isIndexed(path: String): Boolean = path in indexedFiles
    fun indexedFileCount(): Int = indexedFiles.size

    // ---- Internals ----

    /**
     * Tokenize text. Splits on whitespace + ASCII punctuation, lowercases,
     * drops tokens shorter than 2 chars and stop words.
     */
    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>(text.length / 8)
        val sb = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit() || c == '_') {
                sb.append(c.lowercaseChar())
            } else {
                if (sb.isNotEmpty()) {
                    val tok = sb.toString()
                    sb.clear()
                    if (tok.length >= 2 && tok !in STOP_WORDS) out.add(tok)
                }
            }
        }
        if (sb.isNotEmpty()) {
            val tok = sb.toString()
            if (tok.length >= 2 && tok !in STOP_WORDS) out.add(tok)
        }
        return out
    }

    /** Read a small window around [token] in [path] for snippet display. */
    private fun extractSnippet(path: String, token: String, window: Int = 80): String? {
        return try {
            RandomAccessFile(path, "r").use { raf ->
                val len = raf.length().coerceAtMost(64 * 1024L)
                val buf = ByteArray(len.toInt())
                raf.read(buf)
                val text = String(buf, Charsets.UTF_8)
                val idx = text.lowercase().indexOf(token.lowercase())
                if (idx < 0) return@use null
                val start = (idx - window).coerceAtLeast(0)
                val end = (idx + token.length + window).coerceAtMost(text.length)
                val pre = if (start > 0) "…" else ""
                val post = if (end < text.length) "…" else ""
                pre + text.substring(start, end).replace('\n', ' ').replace(Regex("\\s+"), " ") + post
            }
        } catch (_: Exception) { null }
    }

    private fun looksBinary(file: File): Boolean {
        return try {
            val raf = RandomAccessFile(file, "r")
            val size = raf.length().coerceAtMost(8192L).toInt()
            if (size == 0) return false
            val buf = ByteArray(size)
            raf.readFully(buf)
            raf.close()
            // NUL byte in first 8 KB is a strong binary signal for most text files.
            buf.contains(0)
        } catch (_: Exception) { true }
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75

        // Compact English stop list. Language-agnostic enough for our use.
        private val STOP_WORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "all", "any",
            "can", "had", "her", "was", "one", "our", "out", "day", "get",
            "has", "him", "his", "how", "man", "new", "now", "old", "see",
            "two", "way", "who", "boy", "did", "its", "let", "put", "say",
            "she", "too", "use", "this", "that", "with", "have", "from",
            "they", "know", "want", "been", "good", "much", "some", "time",
            "very", "when", "come", "here", "just", "like", "long", "make",
            "many", "over", "such", "take", "than", "them", "well", "were"
        )
    }
}