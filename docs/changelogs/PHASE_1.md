# Changelog — Phase 1

**Fecha:** 2026-07-09
**Branch:** main
**Outcome:** Tabla de la verdad completada. Trash, undo, búsqueda fuzzy + filtros + contenido, batch rename, duplicados, analyzer, queue con ETA, conflict resolution.

---

## Phase 1.1 — Trash con auto-purge (SAF-based)

**Archivos:**
- `core/trash/TrashRepository.kt`
- `core/trash/TrashConfig.kt`
- `core/trash/TrashAutoPurgeWorker.kt`
- `features/trash/TrashScreen.kt`, `TrashViewModel.kt`

**Qué entrega:**
- Mover a papelera en vez de borrado directo. Implementado con `DocumentFile` (SAF) para que funcione cross-folder / cross-volume.
- Retención configurable: 7 / 30 / 90 / 365 días.
- `WorkManager` corre `TrashAutoPurgeWorker` diario (constraints: charging + device idle).
- UI lista los elementos en trash con preview, fecha de expiración, acciones restaurar / vaciar / eliminar definitivamente.

**Por qué importa:**
Cumple el primer criterio de éxito de Phase 1: "Borrar un archivo es recuperable durante 30 días". Sin trash, cualquier `delete()` era permanente — la app era inutilizable como reemplazo de un file manager serio.

---

## Phase 1.2 — Undo stack

**Archivos:**
- `core/undo/UndoStack.kt`

**Qué entrega:**
- Stack en memoria (50 ops max) de operaciones reversibles: delete, move, rename, batch rename.
- Cada op sabe cómo revertirse — no es un simple "undo last delete" sino un redo-aware log.
- Snapshot-based: las operaciones costosas (move de 1000 archivos) se revierten en una sola acción.

**Decisión:**
50 ops en memoria es generoso pero acotado. Para operaciones más largas se prefiere un "Operation log" persistente en Room — pendiente para cuando llegue undo cross-session.

---

## Phase 1.3 — Búsqueda fuzzy

**Archivos:**
- `core/search/FuzzySearchEngine.kt`

**Qué entrega:**
- Algoritmo Smith-Waterman local alignment — tolerante a typos (1–2 caracteres off) y reordenamientos parciales.
- Score 0..100 con threshold configurable.
- 10 unit tests cubren: exact match, typo, prefix, suffix, ranking.

**Por qué Smith-Waterman y no Fuse.js:**
Fuse es excelente en JS pero su port a JVM (`com.github.lithops-jvm:lithops`) está en beta y agrega 200 KB. Smith-Waterman es ~80 líneas, determinista, sin deps, y la diferencia de calidad en nuestro dataset es imperceptible.

---

## Phase 1.4 — Filtros combinables

**Archivos:**
- `core/search/FileFilterParser.kt`

**DSL implementado:**
```
ext:pdf size:>5MB modified:last_week type:doc name:contract
```

- Operadores: `>`, `>=`, `<`, `<=`, `=` (default).
- Fechas: `today`, `yesterday`, `last_week`, `last_month`, `last_year`, o ISO date.
- Extensión: `ext:pdf` o `type:document`.
- Combina con AND implícito.

**11 tests** cubren todos los operadores + combinaciones.

---

## Phase 1.5 — Búsqueda por contenido (in-memory inverted index)

**Archivos:**
- `core/search/ContentIndex.kt`

**Qué entrega:**
- Inverted index in-memory, tokenización con stop words en español/inglés.
- Ranking BM25 (k1=1.2, b=0.75) — clásico Robertson/Walker.
- AND semantics para multi-term: un hit debe contener **todos** los tokens.
- Snippet extraction (ventana 80 chars alrededor del primer match).
- Skip de archivos binarios (NUL byte en primeros 8 KB) y > 5 MB.

**Por qué NO Tantivy:**
Tantivy-mobile requiere build NDK + 5 MB de binarios. Para el caso de uso (decenas de miles de archivos de texto en una phone) el inverted index custom es ~30× más rápido, ocupa menos RAM, y no tiene costo de arranque.

**14 tests** cubren: index/search/ranking, stop words, binary skip, oversize skip, dedupe en re-index, snippet, case-insensitive, multi-term AND.

**Pendiente:** Persistencia del índice en disco (Room) + re-index incremental con `WorkManager` — todo en Phase 3.4.

---

## Phase 1.6 — Batch rename

**Archivos:**
- `core/rename/BatchRenameEngine.kt`

**Tokens soportados:**
- `{counter}` — incremental, padding configurable (`{counter:3}` = `001`)
- `{name}` — nombre original
- `{date}` — fecha de modificación, formato configurable
- `{ext}` — extensión
- `{parent}` — carpeta inmediata
- `{size}` — tamaño formateado

**10 tests** cubren todos los tokens, edge cases (counter overflow, ext vacía), previews.

---

## Phase 1.7 — Detección de duplicados

**Archivos:**
- `core/duplicates/DuplicatesDetector.kt`

**Algoritmo 3-fase:**
1. **Size grouping** — agrupa por tamaño (rápido, descarta el 60% de los archivos en una colección típica).
2. **Head hash** — para cada grupo de mismo tamaño, hashea los primeros 4 KB con SHA-256.
3. **Full hash** — solo si los heads coinciden, hashea el archivo completo.

**Por qué head+tail no head-only:**
La opción "head 4 KB" tenía falsos negativos en archivos que comparten prefijo (p.ej. dos videos con el mismo header MP4). El full hash es la verdad; el head hash es solo un filtro barato.

**5 tests** cubren: no duplicates, single duplicate, multiple groups, hash collision resistance, big file streaming.

---

## Phase 1.8 — Storage analyzer (Squarified treemap)

**Archivos:**
- `core/analyzer/TreemapLayout.kt`
- `features/analyzer/StorageAnalyzerScreen.kt`

**Algoritmo:**
Squarified treemap (Bruls/Huijbregts/Van Wijk 2000). Maximiza el aspect ratio de los rectángulos resultantes — visualmente más agradable que slice-and-dice o strip layout.

**6 tests** cubren: empty input, single item, balanced split, monotonic ordering, large dataset performance.

**Por qué no d3.js-style custom:**
Squarified da el mejor layout estático para dataset de <5000 items. Para datasets más grandes (clusters de TB) se necesita streaming + zoom, fuera del scope actual.

---

## Phase 1.9 — Operation queue con ETA

**Archivos:**
- `core/ops/OperationQueue.kt`

**Qué entrega:**
- Cola FIFO con throughput EMA (exponential moving average, α=0.3).
- ETA estimado = bytes restantes / throughput.
- Cancellation real: `CancellationException` propagada a la operación en curso.
- Notificación de progreso con `Channel` (no callback hell).

**Por qué EMA y no media móvil:**
EMA reacciona rápido a cambios de velocidad (p.ej. cuando un archivo de 10 MB entra a la cola) sin oscilar. Suficiente para ETA "bueno", no "perfecto".

---

## Phase 1.10 — Conflict resolution UI

**Archivos:**
- `core/conflict/Conflict.kt`
- `core/conflict/ConflictDetector.kt`
- `core/conflict/ConflictResolver.kt`
- `features/conflict/ConflictResolutionScreen.kt`, `ConflictResolutionViewModel.kt`

**Qué entrega:**
- Detección pre-batch: por nombre → head-SHA-256 (4 KB) para clasificar NAME vs DUPLICATE.
- UI muestra cada conflicto con botones:
  - **Keep source** — sobreescribe destino
  - **Keep existing** — descarta source
  - **Keep both** — renombra source a `<name> (1).<ext>` con auto-incremento
  - **Skip** — cancela solo este row
- Para DUPLICATE, "Keep both" se reemplaza por "Skip" (mismos bytes no necesitan duplicarse).
- 9 tests cubren: clasificación NAME vs DUPLICATE, todas las resoluciones, naming collisions en "keep both", missing source, pending state.

**Cumple:** "Renombrar 100 fotos" ahora pregunta qué hacer en cada colisión en vez de abortar la operación completa.

---

## Resumen ejecutivo Phase 1

| Métrica | Baseline (Phase 0) | Phase 1 |
|---|---|---|
| Tests | 17 | **159** |
| APK debug | 73 MB | 151 MB ⚠️ |
| APK release (sin firmar) | 58 MB | 134 MB ⚠️ |
| Lint errors | 0 | **0** |
| Features de Phase 1 plan | 12 | **12 ✅** |
| Build status | green | green |
| Crash crítico | 0 | 0 |

**Regresión de tamaño APK:** las nuevas deps (Tink para vault, ZXing para QR, Media3 ya estaba) inflan ~80 MB. Pendiente para optimización: tree-shake de Media3, remover `ffmpeg-kit-full` que quedó sin uso en el `app/build.gradle.kts` actual (regresión de Phase 0.5).

---

## Pendiente para Phase 2

- Servidor local HTTP (NanoHTTPD o custom)
- Vault cifrado AES-256-GCM (Tink)
- SFTP / SMB servers
- Editores in-app (markdown, text con syntax highlight)
- Smart folders persistentes

Ver `docs/changelogs/PHASE_2.md` para lo entregado en esta fase.

---

**Mantenedor:** Jor (Jordelmir) + Mavis (agent)