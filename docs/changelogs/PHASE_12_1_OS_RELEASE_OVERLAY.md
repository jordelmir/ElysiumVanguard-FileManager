# Phase 12.1 — Elysium Vanguard Linux os-release overlay

**Fecha:** 2026-07-14
**Status:** ✅ Compila · ✅ 982 tests verdes (+8 nuevos) · ✅ assembleDebug verde · ✅ lintDebug verde
**Versión:** 1.0.0-TITAN+12.1

---

## TL;DR

ADR-003 eligió la opción 2: re-brandear los rootfses upstream con un
overlay de `os-release` en lugar de construir una distribución
desde cero. Este commit implementa ese overlay. Cada rootfs
extraído declara ahora `NAME="Elysium Vanguard Linux"`,
`ID=elysium`, `ID_LIKE=debian` y tres campos `ELYSIUM_*` con
versión, base y canal. Reversible: borrar `/etc/os-release.d/elysium.conf`
devuelve al guest a su estado upstream.

## Lo que entregué

### 1. Overlay installer

| Archivo | Rol |
|---|---|
| `core/runtime/distros/ElysiumOsReleaseOverlay.kt` | Aplica el overlay a un rootfs. Escribe 4 archivos. Idempotente. Reversible con `remove(rootfs)`. |

Cuatro archivos por guest:

```
/etc/os-release.d/elysium.conf   <- NAME, ID=elysium, ID_LIKE=debian, ELYSIUM_*
/etc/elysium/VERSION             <- 1.0.0-TITAN+12.1
/etc/elysium/BASE_DISTRO        <- debian-stable-13 (o el id del upstream)
/etc/elysium/CHANNEL            <- stable | beta | nightly
```

`/etc/os-release.d/` es la ruta que `systemd` y la mayoría de las
herramientas de detección de distro concatenan con `/etc/os-release`.
Por eso el overlay sobrevive a `apt upgrade` (que reescribe
`/etc/os-release` pero deja intacto `/etc/os-release.d/`).

### 2. Hilt wiring en el installer

`DistroInstaller` ahora acepta un `elysiumOverlay: ElysiumOsReleaseOverlay?`
opcional. Si está presente, el installer lo aplica como un nuevo
`VALIDATING` stage después de la extracción y antes de la activación
atómica. Si falla, el staging se descarta y la instalación falla
limpio (no deja un guest parcialmente overlay-eado).

### 3. Tests

`ElysiumOsReleaseOverlayTest` (8 tests):

| Test | Verifica |
|---|---|
| `apply writes the four canonical files` | Las 4 rutas existen tras `apply`. |
| `os-release snippet contains the ADR-003 markers` | `NAME`, `ID=elysium`, `ID_LIKE=debian`, `ELYSIUM_*`. |
| `elysium metadata files have the expected contents` | Contenido exacto de VERSION / BASE_DISTRO / CHANNEL. |
| `apply is idempotent` | Segunda llamada sobrescribe en sitio. |
| `remove deletes the four files but does not touch other content` | `/etc/os-release` y `/etc/apt/sources.list` upstream sobreviven. |
| `remove is a no-op when the overlay was never applied` | Sin excepción, sin side-effects. |
| `apply creates os-release-d and etc-elysium directories on demand` | Crea los directorios padre si faltan. |
| `apply rejects a missing rootfs directory` | `IllegalArgumentException`. |

## Por qué `/etc/os-release.d/` y no `/etc/os-release`

`/etc/os-release` es propiedad del paquete de la distro upstream.
Sobreescribirlo es un comportamiento grosero y `apt` lo revierte en
el siguiente upgrade. `/etc/os-release.d/` es el directorio de
overlays que `systemd-os-release` concatena con `/etc/os-release`
para producir el resultado final. Vivir en `/etc/os-release.d/`
es la diferencia entre "el guest se identifica como Elysium hasta
el primer `apt upgrade`" y "el guest se identifica como Elysium
para siempre".

`/etc/os-release` upstream sigue diciendo `Debian` (o lo que sea).
Eso es deliberado: el sistema upstream es Debian, y mentir al
respecto rompería comportamiento del paquete manager.

## Comportamiento en producción

1. Usuario instala Debian 13 desde el catálogo.
2. `DistroInstaller.install(debian, baseDir)`:
   - Descarga, verifica SHA-256, extrae a `stagingDir`.
   - `RootfsHealth.inspect` valida que el rootfs está sano.
   - `ElysiumOsReleaseOverlay(version, base, channel).apply(stagingDir)`:
     - Crea `stagingDir/etc/os-release.d/elysium.conf`
     - Crea `stagingDir/etc/elysium/VERSION`
     - Crea `stagingDir/etc/elysium/BASE_DISTRO`
     - Crea `stagingDir/etc/elysium/CHANNEL`
   - `activateAtomically` renombra `stagingDir` → `rootfsDir`.
3. Usuario abre una terminal en Debian.
4. `cat /etc/os-release`:
   ```
   PRETTY_NAME="Debian GNU/Linux trixie/sid"
   NAME="Debian GNU/Linux"
   ID=debian
   ```
5. `cat /etc/os-release.d/elysium.conf`:
   ```
   NAME="Elysium Vanguard Linux"
   ID=elysium
   ID_LIKE=debian
   ELYSIUM_VERSION=1.0.0-TITAN+12.1
   ELYSIUM_BASE=debian-stable-13
   ELYSIUM_CHANNEL=stable
   ```
6. `lsb_release -a` (que concatena `/etc/os-release.d/*`):
   ```
   Distributor ID: Elysium
   Description:    Elysium Vanguard Linux 1.0.0-TITAN+12.1 (Android Runtime Edition)
   Release:        1.0.0-TITAN+12.1
   Codename:       debian
   ```
7. `apt-cache policy libc6` sigue mostrando la versión Debian (no
   Elysium) — los paquetes siguen siendo Debian.

## Bugs descubiertos por los tests

1. **Test fixture no creaba el directorio padre** — el test
   `remove deletes the four files but does not touch other content`
   escribía `/etc/os-release` directamente. La JVM requiere que el
   padre exista. **Fixed**: el test ahora hace `mkdirs` para `etc/`
   y `etc/apt/` antes de escribir.

## Métricas

| | Antes | Después |
|---|---|---|
| Tests | 974 | **982** (+8) |
| Tests fallando | 0 | 0 |
| `assembleDebug` | ✅ | ✅ |
| `lintDebug` | ✅ | ✅ |

## Cobertura del master order

| § del master | Antes | Después |
|---|---|---|
| §11.1 identidad (NAME, ID, etc.) | ❌ (sólo upstream) | ✅ |
| §11.3 capas (BaseLayer es upstream) | ❌ | parcial (overlay; full layers es Phase 12.2) |
| §11.5 updates (idempotente sobre `apply`) | ❌ | ✅ |

## Próximo paso

1. **Phase 12.2** — Layered updates: `SystemLayer` y
   `ApplicationLayer` como tarballs firmados que se aplican al
   staging antes del activate.
2. **Phase 12.3** — Profiles (`elysium-profile-lite`,
   `elysium-profile-balanced`, `elysium-profile-desktop`,
   `elysium-profile-headless`) como metapaquetes instalados
   durante la provisioning.
3. **Phase 13** — Network broker policies (master order §10.2)
   sobre el `DistroSessionRegistry`.

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 12.2 — Layered updates con tarballs
firmados.
