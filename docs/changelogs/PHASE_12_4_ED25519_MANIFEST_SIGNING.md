# Phase 12.4 — Ed25519 manifest signing (ADR-004)

**Fecha:** 2026-07-14
**Status:** ✅ Compila · ✅ 1026 tests verdes (+13 nuevos) · ✅ assembleDebug verde · ✅ lintDebug verde
**Versión:** 1.0.0-TITAN+12.4

---

## TL;DR

Phase 12.2 verificaba el SHA-256 de cada tarball. Eso solo
cierra la mitad del attack surface: un manifest con tarballs
válidos pero authored por un atacante sería aceptado.
Phase 12.4 firma el manifest con Ed25519. La clave pública
viaja en el APK; la privada vive offline. Sin firma válida, no
hay update.

## Lo que entregué

### 1. ADR-004

`docs/adr/ADR-004-rootfs-signing-and-update-model.md` documenta:

- **Ed25519** (JDK 17 first-class; sin deps de terceros).
- **Key model**: offline HSM para `stable` y `beta`; CI secret
  store para `nightly`. Claves públicas en `assets/elysium/keys/<channel>.pub`.
- **Wire format**: `manifest.json` + `manifest.json.sig` (64 raw
  bytes, sobre los bytes exactos del manifest, no re-serialización).
- **Verificación estricta**: no hay escape hatch para "manifest
  sin firma". Fallo de firma = error tipado, no modo degradado.
- **Lo que NO se firma**: tarballs individuales (cubierto por
  SHA-256 transitivo), rootfs upstream (firmado por el distro),
  el `os-release` overlay (parte del APK, no network artifact).
- **Rotación de claves**: dos claves durante un overlap de una
  release, key única después — mismo flow que las distros
  Linux majors.
- **Riesgos**: pérdida de clave (HSM backup geográficamente
  separado), compromiso de HSM (rotación + revocación), Ed25519
  weakness (rotación hard), APK tampering (fuera de scope del
  ADR — responsabilidad de Play Integrity).

### 2. Signer + Verifier

| Archivo | Rol |
|---|---|
| `core/runtime/distros/layer/ManifestSigner.kt` | `sign(bytes, key)`, `signToFile(file, key)`, `generateKeyPair()`, `importPrivate(exported)`, `importPublic(exported)`. PKCS#8 / X.509. |
| `core/runtime/distros/layer/ManifestVerifier.kt` | `verify(bytes, sig, key)`, `verifyFile(file, key)`. Estricto: falla fast en tamaño incorrecto, no confía en SHA-256 alone. |

Ambos usan `Signature.getInstance("Ed25519")` (JDK 17). Sin
Tink, sin Bouncy Castle, sin nuevas dependencias.

### 3. Tests (13 nuevos)

`ManifestSignerVerifierTest`:

| Test | Verifica |
|---|---|
| `sign and verify round-trip` | El par firma/verificación funciona. |
| `a tampered manifest is rejected` | Cambiar un byte del manifest → verify returns false. |
| `a wrong public key is rejected` | Manifest firmado por A no verifica con la clave de B. |
| `a truncated signature is rejected` | 32 bytes lanza IllegalArgumentException. |
| `an extended signature is rejected` | 96 bytes lanza IllegalArgumentException. |
| `public key export and import round-trips` | X.509 SPKI round-trip. |
| `private key export and import round-trips` | PKCS#8 round-trip. |
| `exported public key bytes are stable for a given key` | Determinismo. |
| `two independently generated keypairs are not equal` | Entropía real. |
| `sign to file and verify file round-trip on real disk` | Persistencia on-disk. |
| `verify file rejects a manifest with no signature` | Sin `.sig` → IOException. |
| `empty manifest bytes are rejected` | 0 bytes en el sign → IllegalArgumentException. |
| `verify an empty manifest bytes is rejected` | 0 bytes en el verify → IllegalArgumentException. |

## Bug real descubierto por los tests

El test `an extended signature is rejected` asumió que el
verifier retorna `false` para una firma de tamaño incorrecto.
En realidad, mi `require(signatureBytes.size == 64)` lanza
`IllegalArgumentException` ANTES de que el verifier vea los
bytes. Misma situación que la firma truncada, pero el test
esperaba `assertFalse`. **Fixed**: el test ahora espera
`IllegalArgumentException` (consistente con la firma truncada,
y es la semántica correcta: tamaño incorrecto es input
inválido, no fallo de verificación).

## Decisiones de diseño

- **64-byte hard limit en `verify`** — falla fast en input
  obviamente malformado. La verificación criptográfica real
  (la `verify()` interna de Ed25519) rechazaría cualquier
  tamaño incorrecto de todas formas; el guard es por claridad
  diagnóstica.
- **No se firma el manifest re-serializado** — la firma es
  sobre los bytes exactos del archivo. Esto evita drift de
  canonicalización (orden de campos, espacios, formato de
  números) que ha mordido a otras supply chains.
- **Verifier retorna `false` en fallo, lanza en input inválido**:
  `false` significa "firma criptográficamente inválida";
  `IllegalArgumentException` significa "input malformado antes
  de llegar al crypto". El caller trata los dos casos
  diferente (uno es un ataque, el otro es un bug upstream).
- **Generador de keypair en el mismo objeto que el signer**:
  útil para tests y para el key ceremony. No se llama en
  producción (las claves viven en HSM, no se generan en cada
  build).

## Métricas

| | Antes | Después |
|---|---|---|
| Tests | 1013 | **1026** (+13) |
| Tests fallando | 0 | 0 |
| `assembleDebug` | ✅ | ✅ |
| `lintDebug` | ✅ | ✅ |

## Cobertura del master order

| § del master | Antes | Después |
|---|---|---|
| §11.5 manifest firmado | ❌ (solo SHA-256) | ✅ Ed25519 sobre los bytes |
| §11.5 verificación de integridad | parcial | ✅ SHA-256 + Ed25519 |
| §11.5 clave offline + pública en APK | ❌ | ✅ documentado en ADR-004 |
| §11.5 rotación de claves | ❌ | ✅ documentado |
| §36 ADRs | 3 | **4** |

## Próximo paso

1. **Phase 12.5** — Key ceremony: el team genera el par offline
   y publica la clave pública en `assets/elysium/keys/stable.pub`.
2. **Phase 12.6** — Build pipeline signing step: la CI corre
   `ManifestSigner.signToFile` antes de publicar el manifest.
3. **Phase 13** — Network broker policies (master order §10.2).

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 13 — Network broker policies.
