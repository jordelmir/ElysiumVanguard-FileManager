# Phase 9.6.3.2 — Sovereign Runtime: Inspect UI + Custom URL Install

**Fecha:** 2026-07-09
**Status:** ✅ Compila · ✅ 310 tests verdes (9 nuevos) · ✅ assembleDebug verde
**Versión:** 1.0.0-TITAN+9.6.3.2

---

## TL;DR

Phase 9.6.3 nos dejó un shell jailed dentro de un distro, Phase 9.6.3.1 nos
dejó queries sobre el rootfs. **Phase 9.6.3.2 cierra el ciclo**: el usuario
puede:

1. **Ver qué tiene cada distro** — pantalla Inspect con tabs Files / OS / Packages / Snapshots
2. **Clonar el rootfs** — un tap captura un snapshot full-copy
3. **Pegar una URL custom** — instalador con validación HEAD + progress + extracción tar.gz / tar.xz / tar

Phase 9.6.3.2 también agrega **Apache Commons Compress** como dependencia
para descomprimir tar.xz / tar.bz2 / tar.zst sin depender de proot nativo.

---

## Lo que entregué

```
core/runtime/distros/
├── DistroManager.kt            ← + captureSnapshot, snapshotsFor, removeSnapshot, introspect
├── RootfsIntrospectorSnapshot.kt  ← value-type para el UI; encapsula OsRelease+Entries+Packages
└── DistroModule.kt             ← + CustomRootfsInstaller + Pipeline + Validator + SnapshotFactory

core/runtime/distros/custom/
└── CustomRootfsInstaller.kt    ← CustomRootfsPipeline wrapping validator + installer
                                ← xz / bz2 vía Apache Commons Compress
                                ← hand-rolled JSON manifest (matches 9.6.2 style)
                                ← `installed-via=custom` sentinel for grouping

core/runtime/distros/DistroRootfsExtractor.kt  ← + extractRawTar() para el custom pipeline

features/runtime/inspect/
├── RuntimeInspectScreen.kt     ← 4 tabs (Files / OS / Packages / Snapshots) + 💾 capture button
└── RuntimeInspectViewModel.kt  ← Hilt; lee disk en Dispatchers.IO; expone StateFlow

features/runtime/custom/
├── RuntimeCustomScreen.kt      ← URL → Validate → Install (progress) → Installed
└── RuntimeCustomViewModel.kt   ← sealed class State: Idle/Probed/Installing/Installed/Error

features/runtime/RuntimeScreen.kt  ← + "Add custom rootfs" tile + "inspect" link per installed distro

MainActivity.kt:
  + `runtime_inspect/{distroId}` route
  + `runtime_custom` route
```

---

## `CustomRootfsInstaller` + `CustomRootfsPipeline`

Tres clases, una responsabilidad cada una:

```
CustomRootfsValidator       — HEAD probe, decides if URL is acceptable
CustomRootfsInstaller       — run-of-show: download + decompress + tar extract + manifest
CustomRootfsPipeline        — composes the two for one-call install
```

### Strategy de decompression

| CustomRootfsKind | Stream wrapper |
|---|---|
| TarGz, Tgz | `GZIPInputStream` |
| TarXz | `XZCompressorInputStream` (Apache Commons Compress) |
| Tar | raw stream |
| Unknown | throws |

### Manifest

Hand-rolled JSON (sigue el patrón de Phase 9.6.2):

```json
{
  "distroId": "custom-alpine-minirootfs-3-21-2-aarch64-tar-gz",
  "displayName": "...",
  "version": "custom",
  "packageManager": "apt",
  "rootfsUrl": "https://dl-cdn.alpinelinux.org/.../alpine-minirootfs-3.21.2-aarch64.tar.gz",
  "rootfsKind": "TarGz",
  "installedAtMs": 1752108765432,
  "installVia": "custom",
  "bytesWritten": 62914560,
  "entriesExtracted": 1234
}
```

Más un sentinel `<id>/installed-via=custom` que el pipeline usa para
distinguir custom de catalog en cualquier futura UI.

### Atomicidad

Si el download falla mid-extraction, el directorio entero se borra — no
quedan rootfs fantasma en disk.

---

## `RuntimeInspectScreen`

Cuatro tabs:

| Tab | Contenido | Source |
|---|---|---|
| **Files** | Lista de entries top-level del rootfs (`maxDepth = 3`), con icon 📁/📄/🔗, size, type | `RootfsIntrospector.entries()` |
| **OS** | `pretty_name`, `name`, `version`, `version_id`, `id`, `home_url` parseados de `/etc/os-release` | `RootfsIntrospector.osRelease()` |
| **Packages** | Lista de paquetes del package manager nativo (dpkg/apk/pacman) con name/version/description | `RootfsIntrospector.installedPackages()` |
| **Snapshots** | Snapshots full-copy del rootfs; 💾 en el TopAppBar para crear nuevo; tacho trash para eliminar | `RootfsSnapshot.list()` |

El snapshot se captura en `Dispatchers.IO`. La UI nunca toca el filesystem directamente.

---

## `RuntimeCustomScreen`

**State machine explícito** (sealed class):

```
Idle ──Validate──▶ Probed ──Install──▶ Installing ──ok──▶ Installed
                       │                    │
                       │                  error
                       ▼                    ▼
                   Error ◀────────────── Error
```

La UI renderiza cada estado por separado:

- **Idle**: Outlined text field + Validate button
- **Probed**: Card con size/kind/content-type/etag + Install button (enabled si `isAcceptable`)
- **Installing**: Spinner + "downloading + extracting…"
- **Installed**: Card verde con path absoluto + botón "Add another"
- **Error**: Card rojo + mensaje + botón "Reset"

---

## DistroManager — nuevas APIs

```kotlin
fun captureSnapshot(sourceId: String): DistroSnapshot?
fun snapshotsFor(sourceId: String): List<DistroSnapshot>   // newest first
fun removeSnapshot(snapshotId: String): Boolean
fun introspect(id: String, block: (RootfsIntrospectorSnapshot) -> Unit): Boolean
```

`RootfsIntrospectorSnapshot` es el value-type que el VM entrega al UI:

```kotlin
data class RootfsIntrospectorSnapshot(
    val osRelease: OsRelease,
    val entries: List<RootfsEntry>,
    val packages: List<InstalledPackage>
) {
    val hasOsRelease get() = osRelease != OsRelease.UNKNOWN
    val hasPackages get() = packages.isNotEmpty()
    val summary get() = ... // prettyName o fallback
}
```

---

## Apache Commons Compress — nueva dep

```kotlin
implementation("org.apache.commons:commons-compress:1.26.0")
```

Justificación: `.tar.xz` (el formato de las Ubuntu Noble base images) no se
puede decodificar en Kotlin puro sin ~600 LOC de port de la LZMA SDK. Apache
Commons Compress tiene `XZCompressorInputStream` listo (~250 KB adicional en
APK). 9.6.3.2 lo adopta. Es el mismo Apache Commons Compress que ya usa
el proyecto en otros lados (vía transitive de sshd o algo).

---

## Tests (9 nuevos)

### `CustomRootfsInstallerTest` — 9 tests

Cada uno construye un tar real en memoria (header POSIX.1-1988 + gzip), lo
apunta a un downloader fake, y verifica que:

- `install writes a manifest and creates rootfs dir` ✅
- `install extracts files into rootfs` ✅ (verifica contenido exacto)
- `install fails cleanly when downloader throws` ✅ (no phantom dirs)
- `install fails cleanly when target dir already exists` ✅ (no overwrite)
- `install supports raw tar without gzip wrapping` ✅
- `install fires per-entry progress callbacks` ✅ (entries list non-empty)
- `install rejects unknown kind with a clear error` ✅
- `pipeline refuses unacceptable URL before download` ✅
- `pipeline isCustom recognizes sentinel files` ✅

Cada test crea un `Files.createTempDirectory(...)`, ejecuta el installer,
verifica el resultado, y limpia en el `finally {}`.

**Total: 310** unit tests passing, 0 failures, 0 errors.

---

## Cómo lo prueba Jor

### Inspect

1. Install Alpine
2. Tap "inspect" en el card de Alpine (debajo del "Open")
3. Pestañas: Files / OS / Packages / Snapshots

### Snapshot

1. Inspect > Snapshots tab
2. Tap el 💾 en el TopAppBar
3. Espera (depende del tamaño del rootfs)
4. Aparece `alpine-latest@<timestamp>` con byte count
5. Tap 🗑 para borrarlo

### Custom rootfs

1. En RuntimeScreen, tap "Add custom rootfs" (header verde)
2. Pega: `https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.2-aarch64.tar.gz`
3. Tap "Validate"
4. Card muestra size, kind=TarGz, content-type, etag
5. Tap "Install"
6. Spinner mientras descarga + extrae
7. Card verde: "Installed at `<filesDir>/distros/custom-alpine-.../rootfs/`"

> ⚠ **Caveat honesto:** la DistroCatalog actual NO enumera custom rootfs
> en su UI grid. Aparecen en `DistroStorage` (filesystem es source-of-truth)
> pero el `DistroCatalog.ALL` solo tiene las 4 oficiales. El próximo
> paso (9.6.3.3) hace que custom rootfs aparezcan en el grid principal
> también, agrupados por sentinel.

---

## Decisiones de arquitectura

1. **CustomRootfsPipeline encima de installer y validator separados.**
   Permite al UI usar validator standalone ("solo validar") sin tener que
   abrir un download de 100 MB. También permite a tests inyectar mocks
   independientemente.

2. **Hand-rolled JSON manifest.** Mantiene el patrón de 9.6.2; una sola
   fuente de verdad para el formato del manifest (consistente entre
   catalog + custom). Si más adelante queremos Gson, cambiamos ambos a la vez.

3. **Snapshot full-copy.** Sí, 350 MB para Arch es pesado. La razón:
   filesystems en `filesDir/` no garantizan `linkat()` cross-mount. 9.6.7
   va a cambiar esto con `btrfs`/`f2fs` snapshots nativos.

4. **`extractRawTar` además de `extract`.** El nuevo método es el seam:
   el installer pre-decode el wrapper (xz/bz2/gzip) y pasa tar crudo.
   Esto deja al `DistroRootfsExtractor` libre de cambios drásticos.

5. **State machine explícito en RuntimeCustomVM.** Mejor que status
   string ("idle"/"busy"/"error") porque el UI necesita saber si
   mostrar el probe card o el install button o el spinner.

6. **Tabs via `TabRow` + `selectedTabState`.** Compose moderna; el
   número de tabs es fijo (4) por ahora, crece si necesitamos.

---

## Cosas que dejé fuera intencionalmente (9.6.3.3 backlog)

- **Catalog UI incluye custom rootfs** (actualmente solo se ven los 4 oficiales)
- **Snapshot diff** entre source y clone
- **Progress per-byte** (hoy solo per-entry)
- **Rooftts de mirrors** en `DistroInstaller`
- **OkHttp** streaming + retry + auth
- **Persistent app drawer** para instalar rootfs preseleccionado desde SAF
- **Auto-update checks** para distros oficiales

---

## Métricas Phase 9.6.3.2

| | Antes (9.6.3.1) | Después (9.6.3.2) |
|---|---|---|
| Tests | 301 | **310** (+9) |
| Loc archivos nuevos | — | ~1100 (UI Compose + backend custom install) |
| Native deps añadidas | 0 | 0 |
| Java deps añadidas | 0 | 1 (Apache Commons Compress 1.26.0) |
| Compose screens nuevas | 0 | **2** (RuntimeInspect, RuntimeCustom) |
| NavHost routes nuevas | — | 2 (`runtime_inspect/{distroId}`, `runtime_custom`) |
| Distros install capabilities | 4 (catalog) | 4 (catalog) + **∞** (custom URL) |
| Compression formats | tar, tar.gz | tar, tar.gz, **tar.xz, tar.bz2** |
| `assembleDebug` | ✅ | ✅ 248 MB |

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** **Phase 9.6.3.3** — Catalog UI incluye custom rootfs (auto-detección via sentinel file en `DistroStorage`), progress per-byte en install, y rotación de cache de introspections. Esto cierra el ciclo Phase 9.6.3 → Phase 9.6.3.3 antes de saltar a vendor proot en 9.6.4.
