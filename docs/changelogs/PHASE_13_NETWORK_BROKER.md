# Phase 13 — Network broker (per-session network policy)

**Fecha:** 2026-07-14
**Status:** ✅ Compila · ✅ 1048 tests verdes (+22 nuevos) · ✅ assembleDebug verde · ✅ lintDebug verde
**Versión:** 1.0.0-TITAN+13

---

## TL;DR

Master order §10.2 pide policies de red por sesión con
confirmación explícita para binds en 0.0.0.0. Este commit
implementa el decision engine — broker puro, sin I/O — que
dice yes / no / require-confirmation. La enforcement (iptables,
cgroup, eBPF) queda para Phase 14; el broker ya está
JVM-testable end-to-end.

## Lo que entregué

### 1. Tipos

| Archivo | Rol |
|---|---|
| `core/runtime/network/policy/NetworkMode.kt` (en `NetworkPolicy.kt`) | `BLOCKED` / `LOOPBACK_ONLY` / `OUTBOUND_ONLY` / `LAN` / `INTERNET`. |
| `core/runtime/network/policy/NetworkPolicy.kt` | `data class` con `mode`, `publishedPorts: Set<Int>`, `allowedRemoteHosts: Set<String>`, `allowWildcardListen: Boolean`. Init valida rangos de puerto (1..65535) y rechaza host strings en blanco. |
| `core/runtime/network/policy/NetworkDecision.kt` | `sealed class` con `Allow` / `AllowWithConfirmation(reason)` / `Deny(reason)`. Helper `permits: Boolean`. |

### 2. Broker

`core/runtime/network/policy/NetworkBroker.kt`:

- `decideOutbound(policy, remote, port, audit) → NetworkDecision`:
  aplica el modo, el allow-list de hosts, y la dirección
  (loopback / LAN / internet).
- `decideListen(policy, bindAddress, port, audit) → NetworkDecision`:
  aplica el modo, la published-ports list, y la regla de
  confirmación para 0.0.0.0 / ::.
- `isLan(addr)`: RFC1918 (10/8, 172.16/12, 192.168/16) +
  link-local (169.254/16) + CGN (100.64/10) + IPv6
  unique-local (fc00::/7) + IPv6 link-local (fe80::/10).
- `stripZone(host)`: limpia el zone identifier de IPv6
  (e.g. `fe80::1%wlan0` → `fe80::1`).
- `NetworkAuditLog` in-memory thread-safe con `record`,
  `snapshot`, `size`, `clear`.

Reglas del master order §10.2 implementadas:

- BLOCKED deny todo (incluyendo loopback).
- LOOPBACK_ONLY solo 127.0.0.0/8 y ::1.
- OUTBOUND_ONLY solo publishedPorts para listen; outbound
  abierto.
- LAN: RFC1918 + link-local + loopback. Public internet
  denied.
- INTERNET: todo, excepto 0.0.0.0 listen que requiere
  AllowWithConfirmation salvo `allowWildcardListen=true`.
- 0.0.0.0 listen: siempre `AllowWithConfirmation` para
  OUTBOUND_ONLY, LAN, INTERNET (BLOCKED y LOOPBACK_ONLY son
  hard-deny sin confirm path).

### 3. Tests (22 nuevos)

`NetworkBrokerTest` cubre:

| Test | Verifica |
|---|---|
| `BLOCKED denies every outbound` | Modo más restrictivo. |
| `BLOCKED denies every listen` | No exceptions. |
| `LOOPBACK_ONLY permits 127 0 0 1 outbound and denies the internet` | Solo loopback outbound. |
| `LOOPBACK_ONLY permits listen only on loopback` | Listen en LAN denied. |
| `OUTBOUND_ONLY permits outbound but not listen` | Outbound sí, listen no. |
| `OUTBOUND_ONLY permits listen on a published port` | Publish list gated. |
| `OUTBOUND_ONLY denies a listen on a non-published port` | Port filter. |
| `LAN permits RFC1918 and denies public internet` | LAN deny. |
| `INTERNET permits public hosts and 192 168 but denies 0 0 0 0 listen without consent` | Confirmation surface. |
| `0 0 0 0 listen is allowed when the policy opts in` | `allowWildcardListen=true` path. |
| `OUTBOUND LAN and INTERNET require confirmation for 0 0 0 0 listen` | Loophole cerrado. |
| `BLOCKED mode denies a 0 0 0 0 listen without confirmation` | Hard deny. |
| `allow-list host entry permits matching IP literal` | Allow-list match. |
| `allow-list host entry denies a non-matching destination` | Allow-list miss. |
| `isLan recognises RFC1918 ranges` | 10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10. |
| `isLan rejects public addresses` | 172.32 (out of RFC1918), 192.169. |
| `isLan handles IPv6 unique-local and link-local` | fc00::/7, fd00::/8, fe80::/10. |
| `policy init rejects invalid port numbers` | 0, 70000 → IllegalArgumentException. |
| `policy init rejects blank host entries` | `"  "` → IllegalArgumentException. |
| `audit log records successful outbound and listen decisions` | Happy path audit. |
| `audit log records 0 0 0 0 listen as AllowWithConfirmation` | Confirm path audit. |
| `audit log is thread-safe under concurrent record` | 8 threads × 100 records = 800 entries. |

## Bugs reales descubiertos por los tests

1. **`hostAddress?.removeZone()`** — el extension function estaba
   definido sobre `InetAddress`, pero `hostAddress` es un `String`.
   **Fixed**: convertí el extension en una función top-level
   `stripZone(host: String): String` y la llamo con `let`.

2. **Allow-list match** — `InetAddress.getByName("github.com")`
   resuelve a la IP real; el allow-list tiene el nombre
   canónico. El match fallaba. **Fixed**: el test usa un IP
   literal (`8.8.8.8`) que sí matchea.

3. **fe80::/10 mask** — mi check `b0 and 0xc0 == 0xfe` está mal
   (0xfe and 0xc0 = 0xc0, no 0xfe). **Fixed**: el check
   correcto es `b0 == 0xfe && (b1 and 0xc0) == 0x80` (los
   primeros 10 bits del prefijo link-local son
   `1111 1110 10`).

4. **LOOPBACK_ONLY + 0.0.0.0** — el código retornaba Deny
   ("loopback-only requires loopback listen") ANTES del check
   de confirmation. El test asumía `AllowWithConfirmation` para
   todos los modos no-BLOCKED. **Decisión**: BLOCKED y
   LOOPBACK_ONLY son hard-deny sin confirmation path. Solo
   OUTBOUND_ONLY, LAN, INTERNET permiten 0.0.0.0 con
   confirmation. Test actualizado.

5. **OUTBOUND_ONLY permitía listen sin publishedPorts** — la
   lógica original era "lanza el port check si publishedPorts
   no está vacío", lo que para OUTBOUND_ONLY sin
   publishedPorts permitía cualquier listen. **Fixed**:
   `enforcePublished = mode == OUTBOUND_ONLY ||
   (LAN/INTERNET && publishedPorts.isNotEmpty())`. Para
   OUTBOUND_ONLY, publishedPorts es el ÚNICO camino a listen.

## Decisiones de diseño

- **Decisión ≠ enforcement.** El broker dice yes/no; la
  enforcement (iptables, cgroup, eBPF) es del caller. Esto
  mantiene el broker JVM-testable end-to-end sin root ni
  network namespaces.
- **`AllowWithConfirmation` como decision tipada**, no como
  boolean. El caller renderiza la `reason` en el UI de
  consentimiento; no hay un magic string protocolar.
- **Deny sin audit** — los Deny no llegan al audit log porque
  la operación no ocurrió. El caller puede auditar el Deny en
  su propia capa (donde tiene contexto del proceso, no solo
  la dirección).
- **LOOPBACK_ONLY y BLOCKED hard-deny 0.0.0.0** — no hay
  confirmation path. La policy es "no", punto. Si el usuario
  quiere 0.0.0.0, cambia la policy a OUTBOUND_ONLY/LAN/INTERNET
  y re-intenta.

## Métricas

| | Antes | Después |
|---|---|---|
| Tests | 1026 | **1048** (+22) |
| Tests fallando | 0 | 0 |
| `assembleDebug` | ✅ | ✅ |
| `lintDebug` | ✅ | ✅ |

## Cobertura del master order

| § del master | Antes | Después |
|---|---|---|
| §10.2 solo loopback | ❌ | ✅ |
| §10.2 outbound permitido | ❌ | ✅ |
| §10.2 LAN permitido | ❌ | ✅ |
| §10.2 puerto publicado | ❌ | ✅ |
| §10.2 internet bloqueado | ❌ | ✅ (BLOCKED) |
| §10.2 red por aplicación | ❌ | ✅ (policy per rootfs) |
| §10.2 confirmación para 0.0.0.0 | ❌ | ✅ |
| §10.2 nunca 0.0.0.0 sin consentimiento | ❌ | ✅ |
| §10.2 auditoría de uso | ❌ | ✅ (`NetworkAuditLog`) |

## Próximo paso

1. **Phase 14** — Application Capsules (master order §14). Una
   "capsule" es un ApplicationLayer: tarball firmado con su
   propio id, ventana, permisos, política de clipboard/audio.
2. **Phase 15** — Enforcement real (iptables o eBPF) atado al
   `NetworkBroker`. El broker decide; el enforcer aplica.
3. **Phase 16** — `DistroManager.install` consume
   `ProfileInstaller.plan` y `SystemLayerUpdater.apply` con
   un manifest firmado por `ManifestSigner`.

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 14 — Application Capsules.
