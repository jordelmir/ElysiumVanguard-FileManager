# Phase 12.2 — Layered updates with signed SystemLayer tarballs

**Fecha:** 2026-07-14
**Status:** ✅ Compila · ✅ 997 tests verdes (+15 nuevos) · ✅ assembleDebug verde · ✅ lintDebug verde
**Versión:** 1.0.0-TITAN+12.2

---

## TL;DR

ADR-003 eligió tratar la Elysium Vanguard Linux como un overlay
sobre el rootfs upstream. Phase 12.1 implementó el overlay de
identidad (`/etc/os-release.d/elysium.conf`). Este commit
implementa la otra mitad: el mecanismo de updates firmado
(SHA-256 + snapshot + rollback) que ADR-003 llamó "SystemLayer
y ApplicationLayer".

Cada SystemLayer es un tarball gzip/xz con un id (`elysium-cli`,
`elysium-bridges`, …) y una versión. El updater aplica
secuencialmente, snapshotting la versión anterior antes de cada
apply, y pruning los snapshots viejos. El rollback restaura el
snapshot más reciente.

## Lo que entregué

### 1. Tipos

| Archivo | Rol |
|---|---|
| `core/runtime/distros/layer/SystemLayer.kt` | `data class` inmutable. `id`, `displayName`, `version`, `tarball`, `sha256`, `notes`. Init valida el SHA-256 (64-char lowercase hex). |
| `core/runtime/distros/layer/SystemLayerManifest.kt` | Lista ordenada de SystemLayers con metadatos. Loader JSON que valida el schema version, rechaza duplicados id@version. |
| `core/runtime/distros/layer/UpdateChannel.kt` | `STABLE` / `BETA` / `NIGHTLY` (en el mismo archivo que SystemLayer). |

### 2. Apply

| Archivo | Rol |
|---|---|
| `core/runtime/distros/layer/SystemLayerApplier.kt` | Verifica SHA-256 → extrae el tarball a `_apply.part/` → renombra atómicamente a `elysium-layer-<id>/`. Limpia staging en cualquier fallo. Lanza `LayerHashMismatch` (subclase de `IOException`) si el hash no coincide. |
| `core/runtime/distros/layer/SystemLayerSnapshot.kt` | Snapshot/rollback de un SystemLayer. `take(id, version, sha256)` copia el live dir a `_snapshots/<id>@<version>/` con un `META.json`. `restore(id, expectedLiveSha256)` restaura el snapshot más reciente, con un check opt-in contra downgrades. `prune(keepLatest)` borra snapshots viejos. |

### 3. Orquestación

| Archivo | Rol |
|---|---|
| `core/runtime/distros/layer/SystemLayerUpdater.kt` | Aplica un manifest: por cada layer, snapshot de la versión anterior, apply, marker `VERSION` dentro del live dir, prune. `rollback(id, rootfs)` es restore ciego. `rollbackStrict(id, rootfs)` añade la verificación de fingerprint. |

### 4. Tests (15 nuevos)

`SystemLayerApplierTest` (6):
- `apply extracts the layer tarball into elysium-layer-id`
- `apply overwrites an existing layer with the same id` (verify full replacement)
- `apply rejects a tarball whose sha256 does not match` (LayerHashMismatch)
- `apply requires a real tarball on disk` (init block rejects)
- `apply requires a real destination directory`
- `apply cleans up the staging directory on failure mid-extract` (corrupt tarball)

`SystemLayerUpdaterTest` (9):
- `apply a single layer and verify it lands in the rootfs`
- `apply a second layer and snapshot the first`
- `apply with hash mismatch aborts before mutating the live layer`
- `apply multiple layers in declared order`
- `snapshot prune keeps the latest N entries per layer`
- `rollback restores the most recent snapshot`
- `rollback with no snapshot returns null`
- `manifest load rejects unknown schema version`
- `manifest rejects duplicate id-version pairs`

## Bugs reales descubiertos por los tests

1. **`stripComponents=0` vs `1` para layers** — el `DistroRootfsExtractor`
   que reusamos espera `stripComponents=1` para rootfses (porque el
   tarball tiene un prefijo `./` o un directorio top-level que se
   descarta). Para layers, el primer componente ES el path destino
   (e.g. `opt/cli/version.txt` debe quedar en `opt/cli/version.txt`
   dentro del layer dir, NO en `cli/opt/cli/version.txt`). **Fixed**:
   `SystemLayerApplier` usa `stripComponents=0`.

2. **Mi tar header hecho a mano estaba mal** — el checksum octal
   no estaba bien calculado y el test que escribía el tarball
   manualmente no podía ser leído por el `TarArchiveInputStream`
   de Commons Compress. **Fixed**: usar `TarArchiveOutputStream` y
   `GzipCompressorOutputStream` directamente — round-trip con la
   misma librería que la producción usa para leer.

3. **El test pre-place de la v0.5 fallaba** — `File.writeText` en
   la JVM no crea directorios padre. **Fixed**: `mkdirs()` explícito
   en el fixture.

4. **`SystemLayer.tarball.isFile` rompía el snapshot** — el
   updater pasaba el live dir como `tarball` para construir un
   `SystemLayer` placeholder, pero el init requiere `isFile`.
   **Fixed**: la API del snapshot toma `(id, version, sha256)`
   directamente, no un `SystemLayer`. El updater escribe un
   marker `VERSION` dentro del live dir para registrar qué versión
   fue aplicada.

5. **El check de "downgrade protection" rechazaba rollbacks legítimos**
   — el rollback quería restaurar v1, pero el live era v2 con un
   fingerprint distinto, y el check comparaba fingerprint con el
   snapshot de v1 → "downgrade refused". **Fixed**: el rollback
   es ciego por default (`rollback`); el check estricto es opt-in
   (`rollbackStrict`) para casos donde el caller sabe que el live
   no ha cambiado.

## Decisiones de diseño

- **`stripComponents=0` para layers.** El primer componente del
  tarball ES el path destino dentro del layer dir. Distinto del
  rootfs, donde el primer componente se descarta.
- **Apply destructivo.** Una nueva versión de un layer reemplaza
  completamente la anterior. El snapshot es la red de seguridad.
  Preservar archivos viejos entre versiones requeriría un
  mecanismo de merge de filesystem más complejo (overlayfs),
  reservado para Phase 12.3+.
- **Version marker en el live dir.** Después de cada apply, el
  updater escribe un archivo `VERSION` dentro del layer dir
  con la versión aplicada. Esto permite que un futuro snapshot
  registre "la versión que estoy reemplazando era X" en vez de
  un genérico "previous".
- **Snapshot = copia plana.** No usamos delta. Una layer de 50 MB
  produce un snapshot de 50 MB. Fine por ahora; las layers
  reales son pequeñas. Phase 12.3+ puede mover a copy-on-write.
- **Rollback ciego por default, estricto opt-in.** El check de
  fingerprint es útil solo en ciertos call sites (e.g. "rollback
  a un known-good state" después de un apply parcial). El caso
  general ("el usuario quiere revertir una actualización") no
  debería requerir el check.

## Métricas

| | Antes | Después |
|---|---|---|
| Tests | 982 | **997** (+15) |
| Tests fallando | 0 | 0 |
| `assembleDebug` | ✅ | ✅ |
| `lintDebug` | ✅ | ✅ |

## Cobertura del master order

| § del master | Antes | Después |
|---|---|---|
| §11.3 capas (SystemLayer) | ❌ | ✅ (este commit) |
| §11.5 manifest firmado, SHA-256, atomic, snapshot, rollback | ❌ | ✅ parcial (sin firma real — Phase 12.4) |
| §11.5 protección contra downgrade | ❌ | ✅ opt-in (rollbackStrict) |

## Próximo paso

1. **Phase 12.3** — Profiles como metapaquetes
   (`elysium-profile-lite`, `elysium-profile-balanced`, etc.)
   instalados durante la provisioning del rootfs.
2. **Phase 12.4** — ADR-004 (rootfs signing and update model)
   documenta el modelo de claves. Implementa firma Ed25519 sobre
   el manifest; el APK trae la clave pública; las updates
   rechazan manifests no firmados.
3. **Phase 13** — Network broker policies (master order §10.2).

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 12.3 — Profiles como metapaquetes.
