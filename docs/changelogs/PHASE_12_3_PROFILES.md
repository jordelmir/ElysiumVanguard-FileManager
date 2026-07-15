# Phase 12.3 — Elysium profiles (lite / balanced / desktop / headless)

**Fecha:** 2026-07-14
**Status:** ✅ Compila · ✅ 1013 tests verdes (+16 nuevos) · ✅ assembleDebug verde · ✅ lintDebug verde
**Versión:** 1.0.0-TITAN+12.3

---

## TL;DR

Master order §11.4 define cuatro perfiles. Este commit los
implementa como un enum + un planner que produce el comando
del package manager correcto por familia. Cada perfil es un par
fijo: (a) paquetes upstream a instalar, (b) SystemLayer de
Elysium con la config de marca. La elección es a compile time
— el usuario no puede mezclar paquetes de uno con config de
otro.

## Lo que entregué

### 1. Perfiles

| Archivo | Rol |
|---|---|
| `core/runtime/distros/profile/ElysiumProfile.kt` | Enum con 4 entradas: `LITE` (openbox + lxterminal + pcmanfm), `BALANCED` (xfce4 + thunar + xfce4-terminal), `DESKTOP` (lxqt + qterminal + pcmanfm-qt), `HEADLESS` (sin X). Cada uno declara packages, layerId, layerVersion, RSS y disco estimados. |

```kotlin
LITE:    openbox, obconf, lxterminal, pcmanfm, tint2   ~120 MB RSS, 380 MB disco
BALANCED: xfce4, xfce4-session, xfce4-panel, xfce4-terminal, thunar  ~320 MB RSS, 720 MB disco
DESKTOP: lxqt, lxqt-panel, lxqt-session, qterminal, pcmanfm-qt, lximage-qt  ~480 MB RSS, 980 MB disco
HEADLESS: (vacío)                                          ~40 MB RSS, 200 MB disco
```

`ElysiumProfile.DEFAULT = BALANCED`. `isGraphical` es `false`
solo para `HEADLESS`. `fromId` parsea IDs unknown como `null`
(los callers caen al default).

### 2. Planner

| Archivo | Rol |
|---|---|
| `core/runtime/distros/profile/ProfileInstaller.kt` | `plan(profile, family) → Plan`. El Plan lleva el comando del package manager y los metadatos de la SystemLayer (id, version, displayName) más un placeholder real (file vacío) para que el `SystemLayer` init block no explote. |

Comandos por familia:

| Familia | Comando |
|---|---|
| DEBIAN | `DEBIAN_FRONTEND=noninteractive apt-get install -y <pkgs>` |
| MUSL   | `apk add --no-cache <pkgs>` |
| ARCH   | `pacman -Syu --noconfirm <pkgs>` |
| HEADLESS | `# no upstream packages for headless` (comentario, no-op) |

`DEBIAN_FRONTEND=noninteractive` es importante: en un chroot
de provisioning, `apt` prompt por la zona horaria y el
mailer; sin el env var, la instalación se cuelga en CI.

### 3. Tests (16 nuevos)

`ElysiumProfileTest` cubre:

| Test | Verifica |
|---|---|
| `four profiles are present with stable ids` | El enum tiene exactamente lite/balanced/desktop/headless, en ese orden. |
| `default profile is BALANCED` | Constante `DEFAULT`. |
| `every non-headless profile has at least one upstream package` | Headless es el único sin paquetes. |
| `every profile pairs to a unique SystemLayer id` | No colisión de layer ids. |
| `every profile declares a non-blank layer version` | Sin versiones vacías. |
| `isGraphical is true for graphical profiles and false for headless` | Bandera booleana. |
| `fromId returns the matching profile for a known id` / `returns null for unknown or null ids` | Lookup tolerante. |
| `estimated memory and disk values are monotonic with profile weight` | Headless < Lite < Balanced < Desktop. |
| `installer plan for DEBIAN family uses apt-get` | Comando correcto + DEBIAN_FRONTEND. |
| `installer plan for MUSL family uses apk` | `apk add` con los paquetes correctos. |
| `installer plan for ARCH family uses pacman` | `pacman -S` con `--noconfirm`. |
| `installer plan for headless emits a no-op install command` | Comentario, no comando. |
| `installer plan carries the profile's layer id and version` | Metadata consistente. |
| `installer plan placeholder tarball exists on disk` | File real (vacío), satisface el init de `SystemLayer`. |
| `every package name is well-formed for apt and apk` | Regex `^[a-z0-9][a-z0-9+_.\-]*$`. |

## Bug real descubierto por los tests

El primer intento usó un `File("placeholder-...")` (path relativo
sin archivo en disco) como `tarball` del `SystemLayer`. El
`SystemLayer.init` exige `tarball.isFile`, así que el `plan()`
lanzaba `IllegalArgumentException` en cada llamada. **Fixed**:
`plan()` ahora crea un temp file vacío en disco via
`Files.createTempFile`. El placeholder satisface el init; el
SHA-256 "0".repeat(64) marca "el caller debe reemplazar esto"
y la verificación de hash falla limpio si el caller se olvida.

## Decisión de diseño: planner ≠ ejecutor

`ProfileInstaller` no ejecuta nada. Produce un `Plan` con el
comando y la metadata de la layer; el caller (la pipeline de
provisioning) hace:
1. Render del `installCommand` al usuario (UX de "esto es lo
   que va a pasar").
2. Ejecución del comando en el staging chroot.
3. Descarga del tarball de la SystemLayer.
4. Construcción del `SystemLayer` con el tarball real y el
   SHA-256 del manifest.
5. Hand-off al `SystemLayerUpdater.apply()`.

Esto mantiene el planner JVM-testable end-to-end sin necesitar
un package manager real, y deja la ejecución en un solo lugar
(la pipeline) donde los errores se manejan con el resto del
install.

## Métricas

| | Antes | Después |
|---|---|---|
| Tests | 997 | **1013** (+16) |
| Tests fallando | 0 | 0 |
| `assembleDebug` | ✅ | ✅ |
| `lintDebug` | ✅ | ✅ |

## Cobertura del master order

| § del master | Antes | Después |
|---|---|---|
| §11.4 perfiles (lite/balanced/desktop/headless) | ❌ | ✅ (este commit) |
| §11.4 paquetes por perfil | ❌ | ✅ |
| §11.4 metapaquetes Elysium | ❌ | parcial (la layer del perfil se aplica via Phase 12.2; el metapaquete upstream depende de que el distro lo publique, no en nuestro control) |

## Próximo paso

1. **Phase 12.4** — ADR-004 (rootfs signing and update model).
   Documenta el modelo de claves. Implementa firma Ed25519 sobre
   el manifest; el APK trae la clave pública; las updates
   rechazan manifests no firmados.
2. **Phase 13** — Network broker policies (master order §10.2).
3. **Phase 14+** — Application Capsules (master order §14) sobre
   los SystemLayers de Phase 12.2.

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 12.4 — ADR-004 + Ed25519 manifest signing.
