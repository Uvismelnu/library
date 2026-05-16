package com.medsearch.rag.data.llm

import com.medsearch.rag.data.local.dao.SearchHit

object ExtractiveSummarizer {

    private const val MIN_QUERY_TERM_LENGTH = 3
    private const val OPTIMAL_SENTENCE_MIN = 60
    private const val OPTIMAL_SENTENCE_MAX = 220
    private const val SENTENCE_MIN_VALID = 40
    private const val SENTENCE_MAX_VALID = 400

    private val STOP_WORDS = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas",
        "de", "del", "que", "para", "por", "con", "sin", "como",
        "es", "son", "ser", "está", "están", "este", "esta", "estos", "estas",
        "su", "sus", "le", "lo", "se", "te", "me", "nos",
        "y", "o", "u", "ni", "pero", "más", "menos",
        "qué", "cuál", "cómo", "cuándo", "dónde", "quién",
        "tratamiento", "paciente", "pacientes"
    )

    private val NUMERIC_PATTERN = Regex(
        """\d+[.,]?\d*\s*(mg|g|ml|kg|mcg|µg|ui|mmol|mEq|mmHg|bpm|°C|%|/min|/día|/dia)""",
        RegexOption.IGNORE_CASE
    )

    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+(?=[A-ZÁÉÍÓÚÑ¿¡])")

    private val BIBLIO_PATTERN = Regex(
        """\b(et al\.?|eds?\.|in:|[0-9]{1,2}(st|nd|rd|th)\s+ed\.?|textbook|j\s+am\s+coll|circulation|n\s+engl\s+j)\b""",
        RegexOption.IGNORE_CASE
    )

    private val FIGURE_PATTERN = Regex(
        """\b(figura|figure|tabla|table|cuadro|gráfico|grafico|esquema)\s*[0-9]""",
        RegexOption.IGNORE_CASE
    )

    private val STARTS_WITH_NUMBER = Regex("""^\s*[0-9]""")

    private fun cleanForSentences(raw: String): String {
        return raw
            .replace("[[HIT]]", "")
            .replace("[[/HIT]]", "")
            .replace("«", "")
            .replace("»", "")
            .replace(Regex("(?<![.!?])\\n+"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    private fun isNoise(sentence: String): Boolean {
        val s = sentence.trim()
        if (s.length !in SENTENCE_MIN_VALID..SENTENCE_MAX_VALID) return true
        if (STARTS_WITH_NUMBER.containsMatchIn(s)) return true
        if (BIBLIO_PATTERN.containsMatchIn(s)) return true
        if (FIGURE_PATTERN.containsMatchIn(s)) return true
        val digitCount = s.count { it.isDigit() }
        if (digitCount > s.length * 0.18) return true
        val words = s.split(Regex("\\s+"))
        val allCapsShort = words.count { it.length in 2..5 && it == it.uppercase() && it.any { c -> c.isLetter() } }
        if (words.isNotEmpty() && allCapsShort > words.size * 0.4) return true
        val realWords = words.count { it.length >= 3 && it.any { c -> c.isLetter() } }
        if (realWords < 6) return true
        return false
    }

    data class RankedSentence(
        val text: String,
        val score: Int,
        val bookTitle: String,
        val pageNumber: Int,
        val originalOrder: Int
    )

    fun summarize(
        question: String,
        hits: List<SearchHit>,
        maxSentences: Int = 8
    ): ExtractiveResult {
        if (hits.isEmpty()) {
            return ExtractiveResult(
                summary = "Sin pasajes relevantes. Reformula la búsqueda o indexa más libros.",
                citedHits = emptyList(),
                hadResults = false
            )
        }

        val queryTerms = extractQueryTerms(question)
        if (queryTerms.isEmpty()) {
            return ExtractiveResult(
                summary = "Consulta demasiado genérica. Usa términos clínicos específicos.",
                citedHits = emptyList(),
                hadResults = false
            )
        }

        val allRanked = mutableListOf<RankedSentence>()
        hits.forEachIndexed { hitIdx, hit ->
            val source = hit.fullText.ifBlank { hit.snippet }
            val cleaned = cleanForSentences(source)

            val sentences = SENTENCE_SPLIT.split(cleaned)
                .map { it.trim() }
                .filter { !isNoise(it) }

            sentences.forEachIndexed { sentIdx, sentence ->
                val score = scoreSentence(sentence, queryTerms)
                if (score > 0) {
                    allRanked.add(
                        RankedSentence(
                            text = sentence,
                            score = score,
                            bookTitle = hit.bookTitle,
                            pageNumber = hit.pageNumber,
                            originalOrder = hitIdx * 100 + sentIdx
                        )
                    )
                }
            }
        }

        if (allRanked.isEmpty()) {
            return ExtractiveResult(
                summary = "Se encontraron pasajes pero ninguna oración limpia coincide. " +
                        "Revisa los resultados originales abajo (pueden contener tablas o referencias).",
                citedHits = hits,
                hadResults = true
            )
        }

        val topSentences = allRanked
            .sortedByDescending { it.score }
            .distinctBy { it.text.take(60).lowercase() }
            .take(maxSentences)
            .sortedBy { it.originalOrder }

        val summaryText = buildString {
            appendLine("Oraciones literales de tus libros que coinciden con la búsqueda:")
            appendLine()
            topSentences.forEach { sent ->
                append("• ")
                append(sent.text)
                if (!sent.text.endsWith(".") && !sent.text.endsWith("?") && !sent.text.endsWith("!")) {
                    append(".")
                }
                append(" [${sent.bookTitle}, p. ${sent.pageNumber}]")
                appendLine()
                appendLine()
            }
        }.trimEnd()

        val citedBookPages = topSentences.map { "${it.bookTitle}|${it.pageNumber}" }.toSet()
        val citedHits = hits.filter { "${it.bookTitle}|${it.pageNumber}" in citedBookPages }

        return ExtractiveResult(
            summary = summaryText,
            citedHits = citedHits,
            hadResults = true
        )
    }

    private fun extractQueryTerms(question: String): List<String> {
        return question.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= MIN_QUERY_TERM_LENGTH }
            .filter { it !in STOP_WORDS }
            .distinct()
    }

    private fun scoreSentence(sentence: String, queryTerms: List<String>): Int {
        val sentLower = sentence.lowercase()
        var score = 0
        queryTerms.forEach { term ->
            if (sentLower.contains(term)) score += 3
        }
        if (NUMERIC_PATTERN.containsMatchIn(sentence)) score += 2
        if (sentence.length in OPTIMAL_SENTENCE_MIN..OPTIMAL_SENTENCE_MAX) score += 1
        return score
    }
}

data class ExtractiveResult(
    val summary: String,
    val citedHits: List<com.medsearch.rag.data.local.dao.SearchHit>,
    val hadResults: Boolean
)
