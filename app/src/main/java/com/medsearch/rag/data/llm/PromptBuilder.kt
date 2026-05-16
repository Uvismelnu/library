package com.medsearch.rag.data.llm

import com.medsearch.rag.data.local.dao.SearchHit

object PromptBuilder {

    private const val SYSTEM = """Eres un asistente que PARAFRASEA pasajes médicos. NO eres un experto médico y NO debes usar tu propio conocimiento.

REGLAS ABSOLUTAS:
1. SOLO puedes usar información literal de los PASAJES proporcionados abajo.
2. PROHIBIDO agregar datos, definiciones o explicaciones que NO estén textualmente en los pasajes, AUNQUE creas saberlos.
3. Si los pasajes no explican algo, escribe: "Los pasajes no detallan esto." NO lo inventes.
4. Cada afirmación tuya debe corresponder a un pasaje concreto. Cita así: [Pasaje N].
5. Si no entiendes un pasaje, cítalo textualmente entre comillas en vez de reformularlo.
6. Responde en español clínico, breve. NO des diagnósticos. NO uses palabras que no existan.

EJEMPLO DE LO QUE NO DEBES HACER:
Pasaje: "El soplo continuo del conducto arterioso persistente envuelve a S2."
MAL (inventado): "El conducto arterioso persistente es cuando la presión arterial no está normal."
BIEN (fiel): "Según el pasaje, el soplo continuo del conducto arterioso persistente envuelve a S2 [Pasaje 1]."

Si te apartas de los pasajes, fallas la tarea."""

    private const val MAX_SNIPPET_CHARS = 900
    private const val HIT_MARK_OPEN = "[[HIT]]"
    private const val HIT_MARK_CLOSE = "[[/HIT]]"

    fun build(question: String, hits: List<SearchHit>): String {
        val ctx = StringBuilder()
        hits.forEachIndexed { idx, hit ->
            val rawSource = if (hit.fullText.isNotBlank()) hit.fullText else hit.snippet
            val cleaned = rawSource
                .replace(HIT_MARK_OPEN, "")
                .replace(HIT_MARK_CLOSE, "")
                .replace("«", "")
                .replace("»", "")
                .take(MAX_SNIPPET_CHARS)
            ctx.append("[Pasaje ${idx + 1} | ${hit.bookTitle}, p. ${hit.pageNumber}]\n")
            ctx.append(cleaned.trim()).append("\n\n")
        }

        return buildString {
            appendLine("<start_of_turn>user")
            appendLine(SYSTEM)
            appendLine()
            appendLine("=== PASAJES (única fuente permitida) ===")
            appendLine(ctx.toString().trim())
            appendLine("=== FIN DE PASAJES ===")
            appendLine()
            appendLine("Pregunta del médico: $question")
            appendLine()
            appendLine("Responde SOLO con lo que digan los pasajes, con esta estructura:")
            appendLine()
            appendLine("RESUMEN (parafrasea 2-4 frases fieles a los pasajes, con [Pasaje N]):")
            appendLine()
            appendLine("CITAS TEXTUALES RELEVANTES (copia 1-3 frases exactas entre comillas con su [Pasaje N]):")
            appendLine()
            appendLine("LO QUE LOS PASAJES NO CUBREN (sé honesto):")
            appendLine("<end_of_turn>")
            appendLine("<start_of_turn>model")
        }
    }
}
