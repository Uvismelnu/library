# MedSearch RAG — Búsqueda inteligente en bibliografía médica

App Android nativa en **Jetpack Compose** que indexa una carpeta de libros de medicina en PDF y permite:

1. **Búsqueda full-text** ultra-rápida con SQLite FTS4 (acentos, sinónimos médicos, snippets resaltados)
2. **OCR opcional** para PDFs escaneados (ML Kit on-device)
3. **RAG completo offline** con Gemma 2B (u otro LLM en formato `.task`) vía MediaPipe LLM Inference
4. **Selección gráfica de carpeta** vía Storage Access Framework (no necesita permisos especiales)

Diseñado para uso médico de apoyo bibliográfico, no diagnóstico.

---

## Requisitos

| Item | Versión / Detalle |
|------|-------------------|
| Android Studio | Panda 4 (AI-253.x) o superior |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| JDK | 17 |
| Gradle | 8.10.2 |
| minSdk | 28 (Android 9) |
| targetSdk | 35 |
| Dispositivo recomendado | ARM64, ≥ 6 GB RAM, ≥ 8 GB libres |

> **Importante:** MediaPipe LLM Inference no funciona bien en emuladores x86_64. Para probar el RAG necesitas un dispositivo físico ARM64 (cualquier celular moderno).

---

## Instalación rápida

### 1. Abrir el proyecto

```bash
cd ~/AndroidStudioProjects
unzip MedSearchRAG.zip
```

En Android Studio: **File → Open** → seleccionar la carpeta `MedSearchRAG`.

Si te pide `local.properties`, copia el ejemplo:
```bash
cp local.properties.example local.properties
```
Y ajusta `sdk.dir=` a tu SDK (en Zorin OS suele ser `/home/<usuario>/Android/Sdk`).

### 2. Generar el wrapper de Gradle

La primera vez Android Studio lo regenera solo. Si no, ejecuta desde la raíz:
```bash
gradle wrapper --gradle-version 8.10.2
```

### 3. Sync y build

**Build → Make Project** (Ctrl+F9). La primera sync descarga ~400 MB de dependencias.

### 4. Ejecutar en dispositivo

Conecta tu teléfono Android vía USB con depuración activada y pulsa **Run** (Shift+F10).

---

## Configurar el modelo LLM (Gemma 2B)

La app **no incluye** el modelo en el APK (sería > 1.3 GB). Lo descargas y copias por separado.

### Opción A — Gemma 2B desde Kaggle (recomendada)

1. Crea cuenta gratuita en [Kaggle](https://www.kaggle.com/) si no tienes.
2. Descarga `gemma-2b-it-cpu-int4.task` (~1.3 GB) desde la página oficial de Google MediaPipe:
   `https://www.kaggle.com/models/google/gemma/tfLite/gemma-2b-it-cpu-int4`
3. Acepta los términos de licencia de Gemma.
4. Conecta tu teléfono al PC vía USB en modo "Transferencia de archivos".
5. Copia el `.task` a:
   ```
   /sdcard/Android/data/com.medsearch.rag/files/llm/gemma-2b-it-cpu-int4.task
   ```
6. Abre la app → **Ajustes** → debería aparecer el modelo en "Modelos detectados". Tócalo para cargarlo.

### Opción B — Otros modelos compatibles

Cualquier `.task` compatible con MediaPipe Tasks GenAI 0.10.18+:

- `gemma-2b-it-gpu-int4.task` (más rápido si tu GPU móvil lo soporta)
- `phi-3-mini-4k-instruct-int4.task` (alternativa de Microsoft, similar tamaño)
- Falcon-RW-1B, StableLM, etc.

> Si tienes un Pixel 8/9 o Samsung S24+ con SD 8 Gen 3 o superior, prueba la variante `-gpu-`, es 3-4x más rápida.

---

## Uso

### Primer arranque

1. Aceptar disclaimer médico (una vez).
2. Tocar **"Seleccionar carpeta de libros"** → se abre el selector nativo de Android (SAF).
3. Navegar a la carpeta con tus PDFs y otorgar permiso. El permiso es persistente — no tendrás que volver a hacerlo.
4. Tocar **"Indexar biblioteca"**. La app procesa todos los PDFs en background con notificación de progreso.
   - PDFs nativos: ~30-60 páginas/minuto
   - PDFs escaneados con OCR: ~3-8 páginas/minuto
5. Esperar a "Indexación completa".

### Búsqueda

Escribir cualquier término clínico. Ejemplos que ya funcionan con expansión de sinónimos:

| Escribes | Encuentra también |
|----------|-------------------|
| `IAM` | infarto agudo del miocardio, STEMI, NSTEMI |
| `FA` | fibrilación auricular |
| `SCA` | síndrome coronario agudo |
| `TEP` | tromboembolia pulmonar, embolia pulmonar |
| `DKA` | cetoacidosis diabética |
| `EPOC` | enfermedad pulmonar obstructiva crónica |
| `cetoacidosis` | (búsqueda directa con/sin acentos) |
| `variceal bleeding` | (frase exacta) |

Los resultados muestran:
- Nombre del libro
- Número de página
- Snippet con el término **resaltado en color**

### Resumen RAG

Después de cualquier búsqueda, tocar **"Resumir con IA"**. El LLM local recibe los top-K pasajes y genera:

- **Síntesis:** 2-4 párrafos integrados con citas inline `[Libro, p. N]`
- **Puntos clave:** lista de bullets
- **Limitaciones:** qué no cubren los pasajes

Tiempo típico Gemma 2B int4 en Snapdragon 7+ Gen 2: 15-40 s por resumen.

### Configuración

En **Ajustes**:

- **Modelo LLM:** selecciona entre los `.task` detectados en `/Android/data/com.medsearch.rag/files/llm/`
- **OCR:** actívalo solo si tus PDFs son escaneados (10-50x más lento)
- **Máximo de fragmentos para RAG:** 2-12. Más fragmentos = más contexto pero más lento. Default 6, equilibrado.
- **Borrar índice:** vacía la base de datos y obliga a reindexar.

---

## Arquitectura

```
┌────────────────────────────────────────────────────────────┐
│                    JETPACK COMPOSE UI                      │
│   HomeScreen   SettingsScreen   DisclaimerScreen           │
└─────────────────────┬──────────────────────────────────────┘
                      │
              ┌───────▼────────┐
              │ SearchViewModel│   Hilt + StateFlow
              └───────┬────────┘
                      │
   ┌──────────────────┼──────────────────────┐
   │                  │                      │
┌──▼───────────┐  ┌───▼────────────┐  ┌─────▼──────┐
│SearchRepo    │  │IndexingService │  │PreferencesR│
└──┬─────┬─────┘  └───┬────────────┘  └────────────┘
   │     │            │
   │  ┌──▼─────┐  ┌───▼──────────────┐
   │  │LlmEngn │  │PdfTextExtractor  │
   │  │MediaPpe│  │ PdfBox + ML Kit  │
   │  └────────┘  └──────────────────┘
   │
┌──▼──────────────────────────┐
│ Room DB                     │
│   books                     │
│   page_chunks  ─┐           │
│   page_chunks_fts (FTS4)    │
└─────────────────────────────┘
```

### Pipeline RAG

```
Query "fibrilación auricular"
     │
     ▼
FtsQueryBuilder
  └─ expansión sinónimos: + "fa", + "FA"
  └─ MATCH expression: "fibrilación auricular" OR fa OR FA
     │
     ▼
Room FTS4 search
  └─ tokenizer unicode61 (acentos)
  └─ snippet() con marcadores [[HIT]]
  └─ rank heurístico por frecuencia
     │
     ▼
Top-K pasajes (K = 6 por defecto)
     │
     ▼
PromptBuilder
  └─ system prompt en español, tono clínico
  └─ pasajes con citas inline
  └─ formato Gemma-it (<start_of_turn>user/model)
     │
     ▼
MediaPipe LLM Inference
  └─ Gemma 2B int4, on-device
     │
     ▼
RagResult { answer, usedHits[] }
```

---

## Estructura del proyecto

```
MedSearchRAG/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/medsearch/rag/
│       │   ├── MainActivity.kt
│       │   ├── MedSearchApp.kt
│       │   ├── data/
│       │   │   ├── indexing/IndexingService.kt
│       │   │   ├── llm/
│       │   │   │   ├── LlmEngine.kt
│       │   │   │   └── PromptBuilder.kt
│       │   │   ├── local/
│       │   │   │   ├── MedSearchDatabase.kt
│       │   │   │   ├── FtsQueryBuilder.kt
│       │   │   │   ├── dao/Daos.kt
│       │   │   │   └── entity/Entities.kt
│       │   │   ├── pdf/PdfTextExtractor.kt
│       │   │   └── repository/
│       │   │       ├── PreferencesRepository.kt
│       │   │       └── SearchRepository.kt
│       │   ├── di/AppModule.kt
│       │   ├── ui/
│       │   │   ├── MedSearchApp.kt
│       │   │   ├── SearchViewModel.kt
│       │   │   ├── components/
│       │   │   │   ├── SnippetRenderer.kt
│       │   │   │   └── StatCard.kt
│       │   │   ├── screens/
│       │   │   │   ├── DisclaimerScreen.kt
│       │   │   │   ├── HomeScreen.kt
│       │   │   │   └── SettingsScreen.kt
│       │   │   └── theme/
│       │   │       ├── Color.kt
│       │   │       ├── Theme.kt
│       │   │       └── Type.kt
│       │   └── worker/IndexingWorker.kt
│       └── res/  (drawables, strings, themes, etc.)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── local.properties.example
└── README.md
```

---

## Personalización

### Añadir sinónimos médicos

Editar `data/local/FtsQueryBuilder.kt`, mapa `synonyms`:

```kotlin
"hsa" to listOf("\"hemorragia subaracnoidea\"", "\"hemorragia subaracnoídea\""),
"avc" to listOf("\"accidente vascular cerebral\"", "\"evento vascular cerebral\"", "stroke"),
```

### Cambiar tamaño de chunks

En `data/indexing/IndexingService.kt`:

```kotlin
private val targetChunkSize = 1500     // caracteres por chunk
private val chunkOverlap = 200         // superposición
```

Chunks más grandes → menos hits pero más contexto. Útil si vas a hacer mucho RAG.

### Ajustar el prompt RAG

`data/llm/PromptBuilder.kt`, constante `SYSTEM`. Si cambias de Gemma a Phi-3 o Llama, ajusta también los tokens de turno (`<start_of_turn>`, etc.) al formato del modelo.

---

## Troubleshooting

### "Modelo no cargado" persistente
- Verifica que el `.task` esté en la ruta exacta: `/sdcard/Android/data/com.medsearch.rag/files/llm/`
- Verifica con un explorador de archivos que el archivo no esté corrupto (≥ 1 GB para Gemma 2B int4)
- En Ajustes, toca el chip del modelo para forzar la carga

### MediaPipe crash al cargar el modelo
- Tu dispositivo es x86_64 o no soporta NNAPI/XNNPACK. Usa un teléfono ARM64.
- Hay poca RAM libre. Cierra otras apps.

### Indexación muy lenta
- Si son PDFs escaneados, OCR es el cuello de botella. Considera procesar de noche conectado al cargador.
- Si son nativos pero igual lento: chequea que los PDFs no estén corruptos o cifrados.

### "La carpeta no contiene PDFs"
- SAF a veces no lista PDFs si la carpeta tiene un proveedor raro. Mueve los PDFs a `/sdcard/Documents/MedicalLibrary/` y selecciona ahí.

### "Out of memory" durante OCR
- Reduce el factor `scale` en `PdfTextExtractor.ocrPage()` de 2f a 1.5f.

### Build falla con "Duplicate class kotlinx.coroutines..."
- Sync Project ya debería resolverlo. Si persiste, `./gradlew clean` y vuelve a buildar.

---

## Notas legales y de uso clínico

- Esta app es una **herramienta de apoyo bibliográfico** para profesionales de la salud. No es un dispositivo médico.
- Los resúmenes generados por IA pueden tener errores. **Siempre verifica con la fuente original**.
- Los libros que indexes son de tu propiedad/responsabilidad. La app no comparte ni transmite el contenido a ningún servidor.
- Toda la inferencia LLM ocurre **on-device**. Sin internet después de la primera configuración.

---

## Licencia

Código del proyecto: MIT (puedes adaptarlo libremente).

Dependencias externas mantienen sus respectivas licencias:
- PdfBox-Android: Apache 2.0
- MediaPipe: Apache 2.0
- Gemma 2B: licencia de Google (revísala antes de distribuir comercialmente)
- ML Kit: licencia de Google

---

## Roadmap sugerido (v1.1+)

- [ ] Búsqueda semántica con embeddings on-device (sentence-transformers MiniLM-L6-v2 en TFLite) además de FTS4 para hybrid search
- [ ] Soporte para EPUB además de PDF
- [ ] Modo "consulta clínica": pregunta libre estilo chat con historial
- [ ] Exportar resúmenes a PDF/Markdown
- [ ] Importar tu propio diccionario de sinónimos desde CSV
- [ ] Indexación por capítulo (no solo página) usando TOC del PDF
- [ ] Modo claro/oscuro manual independiente del sistema
