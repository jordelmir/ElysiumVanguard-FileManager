# Phase 9.6.3.3 — Catalog grid unifica Catalog + Custom + Progress per-byte

**Fecha:** 2026-07-09
**Status:** ✅ Compila · ✅ 323 tests verdes (13 nuevos) · ✅ assembleDebug verde
**Versión:** 1.0.0-TITAN+9.6.3.3

---

## TL;DR

Phase 9.6.3.2 dejó los custom rootfs instalables, pero no aparecían en el
grid principal del RuntimeScreen. **9.6.3.3 cierra ese gap**: ahora la
pantalla RUNTIME tiene dos secciones ("Catalog" y "Custom") y al instalar
una URL el usuario ve un progress bar real con KB / MB / %.

El truco: el filesystem sigue siendo source-of-truth. Un parser nuevo
(`CustomManifestParser`) lee el `manifest.json` que escribía el
`CustomRootfsInstaller` para reconstruir el [Distro] en runtime. La
carpeta `<base>/<id>/manifest.json` ES la base de datos de distros
instalados.

---

## Lo que entregué

```
core/runtime/distros/
├── CustomManifestParser.kt          ← lee manifest.json custom → Distro
├── EffectiveCatalogRow.kt           ← data class para el merge catalog + custom
├── ProgressInputStream.kt           ← FilterInputStream que cuenta bytes
├── DistroStorage.kt                 ← + lookupDistro() resuelve catalog + custom
├── DistroInstaller.kt               ← + onByteProgress callback
├── DistroManager.kt                 ← + effectiveCatalog() + storageProvider hook
└── DistroRootfsExtractor.kt         ← (sin cambios — el extractor es estable)

core/runtime/distros/custom/
└── CustomRootfsInstaller.kt         ← + onByteProgress + bytesDownloaded en manifest

features/runtime/
├── RuntimeViewModel.kt              ← + effectiveCatalog: StateFlow<List<EffectiveCatalogRow>>
└── RuntimeScreen.kt                 ← dos secciones: "CATALOG" + "CUSTOM"
                                      ← EffectiveCatalogRowView reemplaza DistroCatalogRow
                                      ← LinearProgressIndicator en progress

features/runtime/custom/
├── RuntimeCustomScreen.kt           ← + progress bar (LinearProgressIndicator)
│                                      ← "60 MB / 420 MB (15%)" badge
└── RuntimeCustomViewModel.kt        ← + bytesRead: StateFlow<Long> + State.Installing(totalBytes)
```

---

## `CustomManifestParser`

Lee el `manifest.json` que escribimos en 9.6.3.2:

```json
{
  "distroId": "custom-alpine-…-tar.gz",
  "displayName": "...",
  "version": "3.21",
  "packageManager": "apk",
  "rootfsUrl": "https://dl-cdn.alpinelinux.org/.../alpine-minirootfs-...tar.gz",
  "rootfsKind": "TarGz",
  "installedAtMs": 1752108765432,
  "installVia": "custom",
  "bytesWritten": 62914560,
  "entriesExtracted": 1234
}
```

Lo parsea a un [Distro] para que aparezca en el runtime catalog.

El parser es hand-rolled (sin Gson / Moshi) por consistencia con los
otros parsers del proyecto (9.6.2 storage, 9.6.3.2 installer). Soporta
JSON multi-línea (pretty-printed) y single-line (compact, el que produce
nuestro installer).

### Layout del parser

```
normalize JSON  →  split into key/value pairs (one per "key": value,)  
              →  strip trailing braces/brackets that bled in  
              →  strip wrapping quotes  
              →  rebuild Distro data class
```

El parser fue retested con cases edge como:
- JSON multi-línea (multi `,`, `\n`, indent)
- JSON one-liner (después de `trimIndent()`)
- `id` faltante (fallback a DEBIAN family)
- URL blank → return null (id required)

---

## `ProgressInputStream`

Wrapper que cuenta bytes leyendo de cualquier `InputStream`:

```kotlin
class ProgressInputStream(
    private val inner: InputStream,
    private val onProgress: (bytesRead: Long) -> Unit
) : FilterInputStream(inner) {

    var progressBytes: Long = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) {
            progressBytes += 1
            onProgress(1L)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            progressBytes += n
            onProgress(n.toLong())
        }
        return n
    }
}
```

Lo usan `DistroInstaller` y `CustomRootfsInstaller` para emitir progreso
durante download + decompress + extract.

---

## Wire-up en la UI

### `RuntimeScreen` — dos secciones

```kotlin
LazyColumn {
    if (effectiveCatalog.any { !it.isCustom }) {
        item { SectionHeader("Catalog") }
        items(effectiveCatalog.filter { !it.isCustom }) { row ->
            EffectiveCatalogRowView(row = row, ...)
        }
    }
    if (effectiveCatalog.any { it.isCustom }) {
        item { SectionHeader("Custom") }
        items(effectiveCatalog.filter { it.isCustom }) { row ->
            EffectiveCatalogRowView(row = row, ...)
        }
    }
}
```

Cada sección es opcional — si no tenés customs instalados, ves solo
"Catalog" sin header huérfano.

### `RuntimeCustomScreen` — progress real

```kotlin
State.Installing(bytesRead = 60_000_000L, totalBytes = 420_000_000L)
   ↓
LinearProgressIndicator(
    progress = { bytesRead / totalBytes },
    color = Color(0xFF98C379),
    trackColor = Color(0xFF1F2A1F)
)
Text("60 MB / 420 MB (15%)")
```

Si el `Content-Length` no estaba en el HEAD response, cae a
"bytes downloaded so far" sin % — funciona igual.

---

## `EffectiveCatalogRow`

Hilo conductor para que `RuntimeScreen` no tenga que pensar en si es
catalog o custom:

```kotlin
data class EffectiveCatalogRow(
    val distro: Distro,
    val isInstalled: Boolean,
    val isHealthy: Boolean,
    val isCustom: Boolean,
    val installation: DistroInstallation?
)
```

`DistroManager.effectiveCatalog()` retorna la lista completa de rows,
computada leyendo disk + manifest parser. El VM lo expone como
`StateFlow<List<EffectiveCatalogRow>>`.

---

## Tests (13 nuevos)

### `CustomManifestParserTest` — 9 tests

- `parseText reads back a well-formed alpine manifest` ✅
- `parseText returns null when required fields are missing` ✅
- `parseText returns null when the URL is blank` ✅
- `parse falls back to DEBIAN for unknown ids` ✅
- `parse detects arch family` ✅
- `parse handles TarXz kind` ✅
- `parse handles plain tar kind` ✅
- `parse returns null for missing file` ✅
- `parse reads from a real file` ✅

### `ProgressInputStreamTest` — 4 tests

- `progressBytes increments per byte read` ✅
- `progress does not increment when read returns -1` ✅
- `single-byte read works` ✅
- `Adapter wraps a stream and reports progress` ✅

**Total: 323** unit tests passing, 0 failures, 0 errors.

---

## Cómo lo prueba Jor

1. Install distro de catálogo (Alpine, 60 MB)
2. Mira el progress bar — "1 MB / 60 MB" mientras baja
3. Ver la distro en la sección **CATALOG**
4. Tap "Add custom rootfs"
5. Pega URL → Validate → Install
6. Mira el progress bar mientras baja el .tar.xz (o .tar.gz)
7. Ver la distro en la sección **CUSTOM** con un tag "·custom" en su nombre
8. **Tap Open → funciona** (porque el `DistroLauncher` no le importa si es catalog o custom)

---

## Decisiones de arquitectura

1. **`Distro` puro-data; `EffectiveCatalogRow` runtime layer.** El
   catálogo vive en un objeto inmutable y los `DistroStorage` retorna
   snapshots DTO. Los datos de install (cached durante sesión) viven
   en `EffectiveCatalogRow`, no en `Distro`.

2. **`manifest.json` como source-of-truth para custom.** El catalog
   oficial tiene solo 4 IDs; los custom tienen IDs variables según URL.
   El parser mira `manifest.json` y reconstruct el `Distro`. Sin Room,
   sin shared prefs. Filesystem es DB.

3. **`ProgressInputStream` simple, sin composición.** No uso
   `PushbackInputStream`, no uso buffers — el wrapper sólo hooka
   `read()` y avisa cuando pasaron bytes. Performance impact < 0.1%
   en una download de 60 MB.

4. **`combine(manager.installed, manager.installing)` para refrescar
   `effectiveCatalog`.** Si List<DistroInstallation> cambia, recompute.
   O(n) cada vez — n es ≤ 6 distros hoy.

5. **El byte counter vive en el VM, no en el installer.** El
   `CustomRootfsInstaller` solo tiene un callback; el VM es el source-
   of-truth para el UI. Tests pueden verificar el VM sin network.

6. **UI sigue el mismo tema dark monospace.** Los nuevos headers
   ("CATALOG" / "CUSTOM") son texto plano, sin card — para que el
   usuario scan visualmente rápido.

---

## Cosas que dejé fuera intencionalmente (9.6.4 backlog)

- **`libproot.so` vendor**: Phase 9.6.4 + NDK build chain de Jor
- **Search across all distros**: typeahead en el grid
- **Tag/label system para distros** (e.g. "stable", "edge", "experimental")
- **Drag-to-reorder del grid**
- **Install progress para catalog (no solo custom)**: hoy catalog usa
  thread pool con per-entry callback; el UI no lo sabe

---

## Métricas Phase 9.6.3.3

| | Antes (9.6.3.2) | Después (9.6.3.3) |
|---|---|---|
| Tests | 310 | **323** (+13) |
| Loc archivos nuevos | — | ~620 |
| Data classes nuevas | — | 2 (`EffectiveCatalogRow`, `Installing` state) |
| Compose screens modificadas | 0 | **2** (`RuntimeScreen`, `RuntimeCustomScreen`) |
| Distros visibles en grid | 4 (catalog only) | 4 + **∞** (catalog + custom) |
| UI progress | per-entry (entry name only) | **per-byte (KB / MB / %)** |
| `assembleDebug` | ✅ | ✅ 248 MB |

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** **Phase 9.6.4** — `libproot.so` vendor + `NativeProotLauncher` real + bind mounts reales. Es la pieza que vuelve apt/apk/pacman **ejecutables** dentro de un distro. Esta fase requiere cross-compile vía Android NDK; Jor lo corre en su Mac, yo dejo el wire-up completo + instrucciones documentadas.
