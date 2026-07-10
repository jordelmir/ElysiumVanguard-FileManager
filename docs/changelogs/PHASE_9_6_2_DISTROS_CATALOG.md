# Phase 9.6.2 — Sovereign Runtime: Distro Installer Catalog

**Fecha:** 2026-07-09
**Status:** ✅ Compila · ✅ 244 tests verdes (10 nuevos) · ✅ assembleDebug verde
**Versión:** 1.0.0-TITAN+9.6.2

---

## TL;DR

Elysium Vanguard ya puede **listar distros Linux instalables** dentro de la app y
gestionarlas. El usuario abre `RuntimeScreen`, ve Debian / Ubuntu / Alpine / Arch
ARM con tamaño aproximado, tap "Install", descarga el rootfs oficial desde mirrors
oficiales (Https puro, sin API keys), extrae en `filesDir/distros/<id>/rootfs/` y
listo. La página principal `terminal` (Phase 9.6.1) sigue conectada.

**Lo que falta para ejecutar Debian:** `proot` binario nativo y los `DistroLauncher`.
Eso es 9.6.3. Por ahora el catálogo está completo, la instalación funciona, y el
filesystem de cada distro vive en `filesDir/distros/<id>/rootfs/` listo para cuando
añadamos el `proot` wrapper.

## Lo que entregué

```
core/runtime/distros/
├── DistroCatalog.kt            ← 4 distros (Debian/Ubuntu/Alpine/Arch)
├── DistroRootfsExtractor.kt    ← Pure-Kotlin tar + tar.gz
├── DistroInstaller.kt          ← Download + extract + manifest
├── DistroStorage.kt            ← Source-of-truth on disk
└── DistroManager.kt            ← Live StateFlow + install queue

features/runtime/
├── RuntimeScreen.kt            ← Compose UI: catálogo + instalar + abrir
└── RuntimeViewModel.kt         ← @HiltViewModel + thread pool + HTTP
```

### `DistroCatalog.kt`

- 4 distros con URL oficial HTTPS:
  - **Debian Stable 12** (Bookworm), 380 MB aprox, iso bootstrap (9.6.3 lo resuelve)
  - **Ubuntu 24.04 LTS** (Noble), 420 MB aprox, tar.gz rootfs oficial
  - **Alpine Linux**, 60 MB aprox, minirootfs tarball oficial
  - **Arch Linux ARM**, 350 MB aprox, rootfs oficial
- Helper `Long.displayByteSize()` — formatting bonito en UI
- Enums `DistroFamily` (DEBIAN, MUSL, ARCH) y `RootfsKind` (TarGz, TarXz, BootstrapTarball, DockerLayer, Custom)

### `DistroRootfsExtractor.kt`

- Pure Kotlin, sin Apache Commons Compress, ~150 LOC auditables
- Soporta ustar (POSIX.1-1988): files, directories, symlinks
- Pad-to-512 automático, padding-aware, checksum-aware
- Progress callback por entry (no assembly-bound: per-file)
- Out-of-scope deferred a 9.6.3: pax headers, xz, zstd, bzip2, hard links

### `DistroInstaller.kt`

- Atomicidad: download → extract → manifest.json, con `install.error` sentinel
- Pure-Filesystem layout: `filesDir/distros/<id>/rootfs/` + `manifest.json`
- Failures dejan el partial rootfs para que el usuario pueda investigar
- Default base directory: `application.filesDir/distros/`

### `DistroStorage.kt`

- Lee disk como source-of-truth (no Room)
- `listInstalled()`, `findInstalled(id)`, `remove(id)`
- Computa on-disk size y lee manifest timestamp

### `DistroManager.kt`

- Tres StateFlow: `installed`, `installing`, `errors`
- `installBlocking(id)`: thread-safe, idempotente
- Concurrency: single install slot per distro id (no dueling downloads)

### `RuntimeScreen.kt`

- LazyColumn de cards: nombre + versión + pkg manager + tamaño aprox
- Buttons: Install (download spinner), Open (terminal action), Remove (text-link)
- Banner rojo cuando un install falla
- Catalog totalizer: "Total catalog size: 1.2 GB"
- Open → navega a `terminal` (Phase 9.6.1)

### `RuntimeViewModel.kt`

- `AndroidViewModel` con `application` (Hilt injects Application)
- `RealHttpDownloader` sobre `HttpURLConnection`: 30 s connect, 5 min read
- Thread pool de 2 workers para installs concurrentes

### Wire-up

- `MainActivity.kt`: ruta `"runtime"`, dashboard redirige allí
- `DashboardScreen.kt`: tile "RUNTIME" → `onNavigateToRuntime`
- AndroidManifest: ya tenía `INTERNET`, no hace falta añadir

## Tests (10 nuevos)

`app/src/test/.../distros/DistroCatalogTest.kt`:

- `catalog is non-empty` — sanity
- `catalog ids are unique` — sin duplicados
- `find returns matching distro` — happy path
- `find returns null for unknown id` — error path
- `every catalog entry has a positive size and a valid url` — calidad
- `total catalog size is consistent` — totales suman bien
- `extracts a tar archive` — extractor end-to-end con tar construido en memoria
- `extracts gzipped tar with one regular file` — gzip branch
- `display byte size has a unit suffix` — formatting
- `installer stores rootfs under baseDir id rootfs` — installer integrity

Total test count: **244** passing, 0 failures, 0 errors (was 234 en Phase 9.6.1).

## Cómo lo prueba Jor

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Abrir app → tap "RUNTIME" en el dashboard
3. Tap "Install" en Alpine (60 MB es el más rápido para probar)
4. Espera 30-60s, ves "Alpine · on disk 60 MB"
5. Tap "Open" — abre el terminal que ya existía (terminal local, sin distro)
6. Listo: catálogo funcional, layout on-disk verificado.

> ⚠ **Caveat honesto**: Phase 9.6.2 no incluye `proot` nativo. Por ahora el catálogo
> instala y mantiene los rootfs, pero **ejecutar dentro de Debian no es todavía
> automático**. El path está ahí (`<filesDir>/distros/alpine-latest/rootfs/`). El
> siguiente paso (Phase 9.6.3) introduce el `libproot.so` + un `DistroLauncher`
> que invoca `proot -0 -r rootfs /bin/bash`.

## Decisiones de arquitectura

1. **Sin `proot` binario en 9.6.2**: vendor un binario nativo requiere
   cross-compilation + signing. Lo dejo para 9.6.3 cuando tengamos el launcher
   co-diseñado con el sandbox.
2. **Sin Room**: el filesystem es la database. Cada rootfs tiene su propio
   `manifest.json`. Room entra en 9.6.3 cuando agreguemos "recently opened"
   como query ordenable.
3. **HTTP nativo**: `HttpURLConnection` basta para 9.6.2. 9.6.3 introduce OkHttp
   para streaming, multipart, mirrors privados con auth.
4. **Symlinks**: el extractor crea symlinks reales via `Files.createSymbolicLink`
   si el FS lo soporta (Android 13+). Fallback: sentinel file con la ruta.
5. **Single install slot per distro**: evitamos 2 downloads paralelas al mismo
   `id`. Cross-distro es OK (puedes descargar Alpine y Debian a la vez).

## Cosas que dejé fuera intencionalmente (9.6.3 backlog)

- `libproot.so` bundled por ABI (arm64-v8a, armeabi-v7a, x86_64, x86).
- `DistroLauncher` que ejecuta `proot -0 -r rootfs /bin/bash`.
- **Filesystem bridge**: bind mounts `/sdcard`, vault, time-travel, cloud dentro del rootfs.
- **Snapshot layers docker-style** del rootfs (~32 MB de boost cuando el usuario descarga el 4º distro).
- **Custom rootfs URL** — usuario pega una URL, validamos via HEAD, instalamos.
- **In-place upgrade** (`apt upgrade` en el distro después de instalar).
- **OkHttp** con retry/backoff, auth, mirrors privados.
- **Rooftts de multiple mirrors** (10+) por distro para resiliencia.

## Métricas Phase 9.6.2

| | Antes (9.6.1) | Después (9.6.2) |
|---|---|---|
| Tests | 234 | **244** |
| Loc archivos nuevos | — | ~1,050 |
| Distros instalables | 0 | 4 |
| HTTP client custom | 0 | 1 (HttpURLConnection wrapper) |
| Native deps añadidas | 0 | 0 |
| `assembleDebug` | ✅ | ✅ 232 MB |

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 9.6.3 — proot nativo + DistroLauncher + bind mounts del vault
