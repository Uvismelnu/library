package com.medsearch.rag.data.llm

import com.medsearch.rag.data.local.dao.SearchHit

/**
 * Construye prompts RAG para Gemma-it / Phi-3-instruct.
 *
 * Estrategia:
 *  - Mensaje de sistema fijo en español, en tono clínico cauto
 *  - Contexto = top N snippets recuperados de FTS, cada uno con su cita
 *  - Pregunta del usuario al final
 *  - Pedimos respuesta estructurada con secciones explícitas
 */
object PromptBuilder {

    private const val SYSTEM = """Eres un asistente bibliográfico médico que ayuda a un profesional de la salud a sintetizar pasajes extraídos de su biblioteca clínica.

Reglas estrictas:
1. Responde EXCLUSIVAMENTE en base a los pasajes proporcionados. No agregues información que no esté ahí.
2. Cita la fuente entre corchetes con formato [Libro, p. N] al final de cada afirmación.
3. Si los pasajes no contienen información suficiente para responder, indícalo con honestidad.
4. Usa lenguaje técnico apropiado para un médico. Responde en español.
5. NO ofrezcas un diagnóstico definitivo; ofrece síntesis bibliográfica.
6. Si los pasajes muestran conflicto entre fuentes, señálalo."""

    private const val MAX_SNIPPET_CHARS = 900
    private const val HIT_MARK_OPEN = "[[HIT]]"
    private const val HIT_MARK_CLOSE = "[[/HIT]]"

    fun build(question: String, hits: List<SearchHit>): String {
        val ctx = StringBuilder()
        hits.forEachIndexed { idx, hit ->
            val cleaned = hit.snippet
                .replace(HIT_MARK_OPEN, "«")
                .replace(HIT_MARK_CLOSE, "»")
                .take(MAX_SNIPPET_CHARS)
            ctx.append("[Pasaje ${idx + 1} | ${hit.bookTitle}, p. ${hit.pageNumber}]\n")
            ctx.append(cleaned).append("\n\n")
        }

        return buildString {
            appendLine("<start_of_turn>user")
            appendLine(SYSTEM)
            appendLine()
            appendLine("=== PASAJES RECUPERADOS ===")
            appendLine(ctx.toString().trim())
            appendLine("=== FIN DE PASAJES ===")
            appendLine()
            appendLine("Pregunta del médico: $question")
            appendLine()
            appendLine("Responde con esta estructura:")
            appendLine("**Síntesis:** (2-4 párrafos, integrando los pasajes con citas inline)")
            appendLine("**Puntos clave:** (lista con viñetas)")
            appendLine("**Limitaciones:** (qué no está cubierto por los pasajes)")
            appendLine("<end_of_turn>")
            appendLine("<start_of_turn>model")
        }
    }
}
