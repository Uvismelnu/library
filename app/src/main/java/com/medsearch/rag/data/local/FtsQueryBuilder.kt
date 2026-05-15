package com.medsearch.rag.data.local

/**
 * Helpers para construir queries FTS4 robustas.
 *
 * SQLite FTS tiene una sintaxis propia (MATCH) que es propensa a errores si
 * el usuario incluye operadores especiales. Aquí los escapamos y construimos
 * frases entre comillas dobles para términos compuestos.
 *
 * También añadimos expansión simple de sinónimos médicos en español.
 */
object FtsQueryBuilder {

    /**
     * Tabla mínima de sinónimos médicos en español <-> abreviaturas.
     * El usuario puede ampliarla; la idea es que buscar "IAM" también
     * encuentre "infarto agudo del miocardio".
     */
    private val synonyms: Map<String, List<String>> = mapOf(
        "iam"   to listOf("\"infarto agudo del miocardio\"", "\"infarto del miocardio\"", "stemi", "nstemi"),
        "sca"   to listOf("\"sindrome coronario agudo\"", "\"síndrome coronario agudo\""),
        "fa"    to listOf("\"fibrilacion auricular\"", "\"fibrilación auricular\""),
        "tep"   to listOf("\"tromboembolia pulmonar\"", "\"embolia pulmonar\""),
        "icc"   to listOf("\"insuficiencia cardiaca\"", "\"insuficiencia cardíaca\""),
        "epoc"  to listOf("\"enfermedad pulmonar obstructiva crónica\""),
        "irc"   to listOf("\"insuficiencia renal cronica\"", "\"enfermedad renal cronica\""),
        "ira"   to listOf("\"insuficiencia renal aguda\"", "\"lesion renal aguda\""),
        "hda"   to listOf("\"hemorragia digestiva alta\"", "\"sangrado de tubo digestivo alto\""),
        "ecg"   to listOf("electrocardiograma", "electrocardiografico"),
        "dka"   to listOf("\"cetoacidosis diabetica\"", "\"cetoacidosis diabética\""),
        "rcp"   to listOf("\"reanimacion cardiopulmonar\"")
    )

    private val ftsReserved = setOf("AND", "OR", "NOT", "NEAR")
    private val unsafeChars = Regex("""[\^"\(\)\*\?:]""")

    /**
     * Construye una expresión MATCH segura.
     *
     * Reglas:
     * - Si el término tiene espacios, se trata como "frase exacta" → "palabra1 palabra2"
     * - Si es una palabra, se permite prefix matching (sufijo *)
     * - Se expanden sinónimos como OR
     */
    fun build(rawTerm: String): String {
        val term = rawTerm.trim().lowercase()
        if (term.isEmpty()) return ""

        val sanitized = term.replace(unsafeChars, " ").trim()
        val tokens = sanitized.split(Regex("\\s+")).filter { it.isNotBlank() && it !in ftsReserved.map { r -> r.lowercase() } }

        val baseExpr = when {
            tokens.isEmpty() -> return ""
            tokens.size == 1 -> "${tokens[0]}*"
            else -> "\"${tokens.joinToString(" ")}\""
        }

        val synList = synonyms[sanitized] ?: emptyList()
        return if (synList.isEmpty()) baseExpr
        else (listOf(baseExpr) + synList).joinToString(" OR ")
    }

    /**
     * Versión "plana" del término (sin operadores), útil para calcular
     * un ranking aproximado por frecuencia en el chunk.
     */
    fun plainForm(rawTerm: String): String =
        rawTerm.trim().lowercase().replace(unsafeChars, " ").trim()
}
