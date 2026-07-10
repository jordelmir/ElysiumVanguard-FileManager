# Phase 9.6.3.1 — Sovereign Runtime: Rootfs Introspection + Snapshots + Custom URLs

**Fecha:** 2026-07-09
**Status:** ✅ Compila · ✅ 301 tests verdes (24 nuevos) · ✅ assembleDebug verde
**Versión:** 1.0.0-TITAN+9.6.3.1

---

## TL;DR

Phase 9.6.3 te dejó dentro de un distro con un shell jailed. Phase 9.6.3.1 te
deja **saber qué hay dentro** del distro, **clonar el rootfs en un snapshot**
para experimentar sin miedo, y **pegar una URL** para instalar un rootfs
custom que no está en el catálogo oficial.

Tres capacidades, una sola línea de pensamiento: el rootfs ya es una DB en
disco, conectémosle *queries*.

---

## Lo que entregué

```
core/runtime/distros/introspector/
├── RootfsEntry.kt              ← data class: relativePath, isDirectory, isSymlink, sizeBytes
├── OsRelease.kt                ← subset of /etc/os-release
├── InstalledPackage.kt         ← common schema for dpkg/apk/pacman rows
└── RootfsIntrospector.kt       ← entries(depth) · osRelease() · installedPackages()

core/runtime/distros/snapshot/
└── RootfsSnapshot.kt           ← DistroSnapshot · SnapshotIds · RootfsSnapshot(capture/remove/list)
                                ← recursive copy with symlink preservation + fs fallback

core/runtime/distros/custom/
└── CustomRootfsValidator.kt    ← UrlProbe · CustomRootfsKind · CustomRootfsHttpProbe
                                ← RealCustomRootfsHttpProbe via HttpURLConnection HEAD
                                ← rejects >2GB, only accepts tarball filenames
```

---

## `RootfsIntrospector`

Tres queries que responden las preguntas más importantes **antes** de que el
usuario se comprometa a bootear o a instalar paquetes.

### `entries(maxDepth: Int = 3): List<RootfsEntry>`

Walk recursivo del rootfs. Devuelve rows planas (no un `TreeNode`); el UI agrupa
por parent al renderizar. Depth configurable para que la pantalla "Inspect"
pueda mostrar top-level sin tener que listar todo `/usr/bin` (hay miles de
archivos ahí).

Usa `java.nio.file.Files.isSymbolicLink(path)` para marcarlos. Tam de archivos
vía `File.length()`. Sí, no es lo más rápido para `dpkg` con 50K archivos,
pero para la "exploración on-demand" sobra.

### `osRelease(): OsRelease`

Parsea `etc/os-release`. Si el archivo no existe o está malformado, devuelve
`OsRelease.UNKNOWN`. La intención es que la UI pueda decir "I see
Alpine Linux v3.21" aunque la distro haya sido un custom rootfs que el
usuario pegó.

### `installedPackages(): List<InstalledPackage>`

Tres parsers, uno por familia de paquete manager. La estrategia de detección
es por archivo marcador — no necesitamos conocer el distro id:

| Familia | Archivo leído | Formato |
|---|---|---|
| DEBIAN (Debian/Ubuntu) | `var/lib/dpkg/status` | RFC-822-ish stanzas separadas por blank lines |
| MUSL (Alpine) | `lib/apk/db/installed` | Single-letter keys (`P:`, `V:`, `c:`) |
| ARCH | `var/lib/pacman/local/*/desc` | `%NAME%`, `%VERSION%`, `%DESC%` stanzas |

Si ningún archivo existe → `emptyList()`. Custom rootfs que no traiga pm DB
puede ver "no package metadata available" en la UI.

---

## `RootfsSnapshot`

El segundo feature es clonación honesta del rootfs. Strategy:

- Source: `<baseDir>/<sourceId>/rootfs/`
- Dest: `<baseDir>/<sourceId>@<timestamp>/rootfs/`
- `capture()` corre `cp -r` recursivo preservando symlinks cuando el FS lo
  permite; si no, escribe un sentinel file `symlink→<target>`.

Razón de hacer copia completa y no hardlinks: estamos en `filesDir/` que en
muchos devices Android es un F2FS o ext4 sin `linkat()` cross-mount. Cuando
`NioFiles.createSymbolicLink` falle por UnsupportedOperationException (algunos
devices), caemos al sentinel file. La copia es O(rootfs size) pero solo se
ejecuta on-demand desde la UI, no en background.

`SnapshotIds.next(sourceId, epochMs = now)` produce un id determinista
como `alpine-latest@2026-07-09T1612-23Z-UTC` (sortable lexicográficamente).

`remove(snapshotId)` borra recursivo. `list()` enumera todo bajo `<baseDir>`
cuyo nombre contenga `@`.

---

## `CustomRootfsValidator`

Pegar una URL de rootfs es útil porque hay distros que no vamos a tener en el
catálogo oficial: Kali custom builds, Void Linux, Alpine con musl edge, etc.

El validator hace:

1. HEAD request con `connectTimeout = 15s` y `readTimeout = 15s`.
2. Captura Content-Length, Content-Type, ETag, Last-Modified.
3. Infiere el `CustomRootfsKind` desde la extensión del filename:
   `tar.gz / .tgz` → `TarGz / Tgz`, `tar.xz / .txz` → `TarXz`, `tar` → `Tar`.
4. Acepta SOLO si:
   - reachable
   - looks like a tarball
   - Content-Length está ausente O es < 2 GB (cap defensivo para no
     tragarnos un Docker layer de 50 GB por un typo)

El validator **NO** descarga. El download real (con HTTP long-poll, multipart,
auth) sigue siendo `DistroInstaller` + `RealDistroHttpDownloader` (Phase 9.6.2).
Para esta phase solo agregamos la fase 0 del install: "¿querés instalar esto sí
o no?".

---

## Tests (24 nuevos)

### `RootfsIntrospectorTest` — 12 tests

- `entries returns top-level files and directories` ✅
- `entries includes depth-N children when maxDepth greater than 1` ✅
- `osRelease parses Alpine pretty name` ✅
- `osRelease returns UNKNOWN when file missing` ✅
- `osRelease tolerates malformed content` ✅
- `installedPackages reads Alpine apk database` ✅
- `installedPackages reads Debian dpkg status` ✅
- `installedPackages reads Arch pacman local` ✅ (¡me peleé con `map["NAME"]` y
  aprendí a no agregar `\n` por blank lines!)
- `installedPackages returns empty list when no package manager is detected` ✅
- `entries handles symlinks without throwing` ✅
- `entries rejects missing rootfs` ✅ (IAE)
- `entries rejects maxDepth less than 1` ✅ (IAE)

### `RootfsSnapshotTest` — 5 tests

- `SnapshotIds produces sortable ids` ✅
- `capture clones the rootfs under a snapshot directory` ✅
- `capture fails cleanly when source rootfs is missing` ✅
- `remove deletes the snapshot directory` ✅
- `list returns snapshots sorted newest first` ✅

### `CustomRootfsValidatorTest` — 7 tests

- `probe flags a tarball as acceptable when HEAD returns 200` ✅
- `probe flags a tar-xz as TarXz` ✅
- `probe flags a tgz url as Tgz kind` ✅
- `probe marks a non-tarball URL as not acceptable` ✅
- `probe rejects tarballs over 2GB` ✅
- `probe handles unreachable hosts gracefully` ✅
- `blank URL is rejected before probe runs` ✅

**Total: 301** unit tests passing, 0 failures, 0 errors.

---

## Cómo lo prueba Jor

### Introspect
1. Abre app → RUNTIME → Install Alpine → espera
2. Tap en el card de Alpine (no "Open", un área info)
3. Verás: `prettyName`, `packages.size`, lista top-level

### Snapshot
1. Install Alpine → tap "Snapshot" (UI hook, no implementado en esta fase;
   la API está lista: `RootfsSnapshot(baseDir).capture("alpine-latest")`)
2. Aparece `<baseDir>/alpine-latest@<ts>/` con copia completa
3. List te lo muestra newest-first

### Custom URL
1. Abre runtime → "Add custom" (UI hook, próxima fase)
2. Pega `https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.2-aarch64.tar.gz`
3. Validación: 200, 60 MB, tar.gz → ✅
4. UI confirma y dispara el install real

> ⚠ **Caveat honesto:** la UI de Inspect/Snapshot/Custom no está cableada
> en 9.6.3.1 — el **backend está completo y testeado**, la UI es el
> siguiente paso. Phase 9.6.3.2 agrega esos Compose screens.

---

## Decisiones de arquitectura

1. **Source-of-truth = filesystem.** El introspector no tiene cache,
   no usa Room; cada call lee del disk. El rootfs nunca cambia entre
   calls (solo cambian con `DistroInstaller` o `RootfsSnapshot.capture`),
   así que cache no aporta.

2. **Tres parsers distintos, tres líneas mentales.** dpkg usa RFC-822,
   apk usa single-letter keys, pacman usa `%KEY%`. En lugar de
   normalizar a un solo formato, los tres viven en su propia helper
   y se eligen por archivo marcador. Si querés agregar **Fedora**
   o **Arch con pacman-sync** en 9.7.8, agregás un cuarto
   parser al lado de los tres.

3. **Snapshot full-copy, no hardlinks.** 9.6.3.1 honesto: los filesystems
   típicos en `filesDir/` de Android no garantizan `linkat()` cross-mount.
   Full-copy es lento pero predecible. Phase 9.6.7 (en el roadmap) lo
   cambia por copy-on-write con `btrfs`/`f2fs` snapshots nativos.

4. **Custom URL validator NO descarga.** Es solo un HEAD. El download
   real lo maneja `DistroInstaller` con `RealDistroHttpDownloader`.
   Esto mantiene el surface de 9.6.3.1 chico — un file, 178 LOC.

5. **2 GB cap defensivo.** Imaginate pegar una URL de un Docker image
   layer. Sería una tragedia descargar 50 GB antes de notar el error.
   El cap es un trade: hoy 2 GB es suficiente para cualquier distro
   rootfs; si Jor necesita más, se sube al `validate(url, maxBytes)`.

---

## Cosas que dejé fuera intencionalmente (9.6.3.2 backlog)

- **UI Compose** para Inspect / Snapshot / Custom URL (`RuntimeInspectScreen`).
- **Install del custom URL**: hoy el validator te dice "ok"; falta el
  `downloadAndExtract(url, baseDir, kind)` que use el kind inferido.
- **Rooftts de mirrors** en el catálogo (10+ mirrors por distro).
- **OkHttp** streaming + retry + auth (Phase 9.7).
- **Rootfs diff** (compare dos snapshots / source vs custom).
- **Pacman -Qii**: full info; hoy solo name/version/description.

---

## Métricas Phase 9.6.3.1

| | Antes (9.6.3) | Después (9.6.3.1) |
|---|---|---|
| Tests | 277 | **301** (+24) |
| Loc archivos nuevos | — | ~640 |
| Filesystems readers | 0 | 3 (dpkg / apk / pacman) |
| Snapshot capability | — | Full copy + symlink-aware + sortable ids |
| Custom URL gate | — | HEAD probe + cap defensivo + kind infer |
| `assembleDebug` | ✅ | ✅ 248 MB |

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** **Phase 9.6.3.2** — UI Compose para Inspect / Snapshot / Custom URL + el download real del custom rootfs. Es lo que vuelve estas APIs visibles y usables desde la pantalla RUNTIME.
