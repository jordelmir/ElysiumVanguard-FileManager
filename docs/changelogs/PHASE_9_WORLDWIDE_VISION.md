# Elysium Vanguard — Worldwide Vision (Phase 9+)

> "Vamos a crear algo que nadie ha tenido hasta hoy.
> Puede pesar 20GB. Que me vale.
> Con terminal, con distros de Linux instalables, dentro."
>
> — Jor, 2026-07-09

---

## Lo que cambió

Phase 8 terminó con un **file manager Android muy capaz** a nivel técnico. Eso era el plan
cuando pensábamos "es una app Android para Play Store".

**Ya no.**

Elysium Vanguard no es una app Android. Es **el primer runtime soberano Android+Linux del
planeta**: file manager cross-platform, AI-native, decentralized, quantum-ready, con un
terminal completo y la capacidad de instalar distros de Linux dentro de la propia APK,
sin root, sin kernels custom, sin hacks raros.

Lo que viene es construir **algo que nadie ha tenido**.

---

## La promesa (versión extendida)

**Elysium Vanguard es el ÚNICO software en el mundo donde:**

- Buscas "el contrato de marzo" sin saber el nombre del archivo, en cualquiera de tus devices, en
  español/inglés/mandarin/árabe, on-device, sin enviar nada a la nube.
- Mueves un slider y viajas en el tiempo por todo tu filesystem.
- Compartes un archivo de 50GB con alguien del otro lado del mundo sin servidor central,
  cifrado E2EE, expirando en 24h.
- Le dices "Elysium, organiza mis Downloads" y lo hace con un LLM on-device que propone
  un plan, tú apruebas, y se ejecuta.
- Tienes el mismo filesystem en el teléfono, la tablet, el laptop y la web, sincronizado
  peer-to-peer.
- Abres un PDF en japonés y lo ves en español en tiempo real, sin internet.
- Tus archivos están cifrados con criptografía post-quantum.
- Abres un terminal real con bash, ssh, vim, tmux, htop, todo dentro de la app.
- Instalas Debian, Ubuntu, Alpine, Arch, Kali o Fedora con un tap — sin root, dentro de la APK.
- Corres apps gráficas de Linux (Firefox, GIMP, VS Code, Blender) sobre tu Android, viéndolas
  en una ventana dentro de Elysium o en pantalla completa.
- El filesystem de Elysium (vault, time-travel, semantic search) **lo ve** Linux también.
- Funciona sin internet: BLE + WiFi Direct + ultrasonido.
- Entiende tu voz en cualquier idioma.
- Tiene CLI para servidores (Rust).
- Es open source, propiedad del usuario.

**Esto no es un producto. Es un sistema operativo.**
**Esto no es un file manager. Es un runtime de soberanía digital.**

---

## Arquitectura objetivo

```
┌──────────────────────────────────────────────────────────────────────┐
│  Elysium Vanguard Universe                                          │
│                                                                      │
│  ┌─────────┐  ┌─────────┐  ┌──────────┐  ┌────────┐         ┌────┐  │
│  │ Android │  │   iOS   │  │ Desktop  │  │  Web   │         │CLI │  │
│  │ (Kotlin)│  │(SwiftUI)│  │(KMP/CMP) │  │  (SPA) │         │Rust│  │
│  └────┬────┘  └────┬────┘  └────┬─────┘  └────┬───┘         └─┬──┘  │
│       │             │             │             │                │     │
│       └─────────────┴──────┬──────┴─────────────┘                │     │
│                            │                                      │     │
│                  ┌─────────▼──────────┐                           │     │
│                  │  elysium-core-net   │  P2P mesh + E2EE         │     │
│                  │  (Kotlin Multipl.)  │  WebRTC + BLE + WFD      │     │
│                  └─────────┬──────────┘                           │     │
│                            │                                      │     │
│   ┌────────────────────────┼────────────────────────────┐         │     │
│   │                        │                            │         │     │
│ ┌─▼──────────┐    ┌────────▼────────┐         ┌──────────▼─┐       │     │
│ │ elysium-ai │    │ elysium-crypto  │         │ elysium-fs │       │     │
│ │ (ONNX+LLM) │    │ (PQ-safe+Tink)  │         │  (VFS layer)│      │     │
│ └────────────┘    └─────────────────┘         └─────────────┘       │     │
│                                                                      │
│   ┌───────────────────────────────────────────────────────────┐     │
│   │  elysium-protocols (SMB, WebDAV, FTP, SFTP, IPFS,        │     │
│   │  Torrent, NFS, S3, Drive, Dropbox, Box, OneDrive, ...)   │     │
│   └───────────────────────────────────────────────────────────┘     │
│                                                                      │
│   ┌───────────────────────────────────────────────────────────┐     │
│   │  ⭐ elysium-runtime ⭐  (NEW — Phase 9.6)                  │     │
│   │  • PTY-backed terminal emulator (Termux-core-derived)      │     │
│   │  • proot + proot-distro: install Debian/Ubuntu/Alpine/Arch│     │
│   │  • X11/VNC forwarder → apps GUI Linux en ventanas nativas │     │
│   │  • Filesystem bridge: /sdcard + Elysium vault + SAF tree  │     │
│   │  • SSH client with X11-forwarding (reuses Apache MINA)    │     │
│   └───────────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────────┘
```

**Kotlin Multiplatform** como lengua franca: lógica compartida core. UI nativa por plataforma.

---

## Las 12 promises tecnológicas — ninguna existe hoy junta

### 1. Búsqueda semántica universal on-device
ONNX + all-MiniLM-L6-v2 + HNSW Kotlin. <200ms en 100K chunks.

### 2. LLM agent con file-ops sandbox
Phi-3-mini on-device + function calling tipada. "Organiza mis Downloads" → propuesta → aprobación → ejecución.

### 3. Time-travel filesystem
Snapshot engine estilo COW sobre SQLite + checksums. Slider temporal en UI. Restore desde cualquier punto.

### 4. P2P mesh sharing sin internet
Multi-transport: BLE + WiFi Direct + WebRTC + mDNS + ultrasonic pairing. E2EE por defecto. Grupos de 10+ devices.

### 5. Real-time co-editing universal
CRDT custom (Yjs-like) para PDF/MD/txt/code. E2EE. Funciona peer-to-peer o relay.

### 6. Post-quantum encryption
Kyber + Dilithium + AES dual-layer. Quantum-safe vault y shares.

### 7. Real-time translation of file contents
NLLB-200-distilled-600M on-device. Traduce en sitio: PDF, MD, txt. Sin internet.

### 8. AR/VR file explorer
Spatial UI Quest 3 + Vision Pro. Cards flotantes. Gestures. Storage como ciudad 3D.

### 9. Decentralized identity + smart contracts
DIDs (`did:elysium`). Compartir con handle directo. Smart contracts de expiración en L2.

### 10. Self-destructing shares
Time-lock cryptography (BLS + VDF). Imposible recuperar aunque tengas el archivo offline.

### 11. Universal protocol matrix
20+ protocolos (SMB/WebDAV/FTP/SFTP/NFS/AFP/IPFS/BitTorrent/S3/Drive/Dropbox/Box/OneDrive/iCloud/Backblaze/Wasabi/Azure Blob). Mismo árbol virtual. Búsqueda federada sobre todos.

### 12. ⭐ Sovereign Linux Runtime ⭐  (NUEVO — Phase 9.6)
**La promesa de esta sesión.** El FM se vuelve un runtime Android+Linux.

---

## ⭐ Phase 9.6 — Sovereign Linux Runtime (DETALLE)

### Qué es

Una capa dentro de Elysium que ofrece:

1. **Terminal emulator completo** dentro de la app.
   - PTY real (no fake shell).
   - Múltiples tabs / sesiones.
   - ANSI colors, scrollback, búsqueda, copy/paste.
   - Atajos: Ctrl+C, Ctrl+L, arrows, history.
   - Temas: dark, light, solarized, hacker.
   - Snippets library.
   - Widget de "quick command" desde el launcher.

2. **Distros de Linux instalables** dentro de la APK.
   - Catálogo: Debian, Ubuntu, Alpine, Arch, Kali, Fedora, openSUSE, Void, CentOS, Rocky, NixOS.
   - Instalación con un tap. Rootfs descargado desde mirrors oficiales.
   - Sin root, vía `proot`.
   - Snapshots del rootfs tipo Docker layers.
   - Multiplex: varias distros simultáneas.
   - Customización: instalar paquetes con `apt`/`apk`/`pacman`/`dnf`.
   - Persistencia: bind mount de `/sdcard` y folders Elysium dentro del rootfs.

3. **GUI apps de Linux en ventanas nativas Android**.
   - X11/VNC server dentro del distro + cliente VNC embebido en Compose.
   - Apps soportadas: Firefox, GIMP, VS Code, Blender, LibreOffice, GIMP, Inkscape, Audacity.
   - Multi-ventana flotante, resize, fullscreen.
   - Forwarding vía SSH tunnel (reusa Apache MINA SSH client).
   - Wayland vía `weston` + forwarding (fase avanzada).

4. **Filesystem bridge** — el truco que vuelve esto único.
   - El rootfs de la distro ve:
     - `/sdcard` (todo el storage Android)
     - `/elysium/vault` (tus archivos cifrados, decrypted on-demand)
     - `/elysium/cloud/Drive`, `/elysium/cloud/Dropbox`, ...
     - `/elysium/time-travel/2024-03-15` (cualquier snapshot histórico)
     - `/elysium/tags` (tag system como filesystem virtual)
   - El LLM agent también ve esto — puede correr `find`, `grep`, `ollama run` directamente sobre tu filesystem.

5. **Integración con todo lo demás**.
   - `sshd` server de Elysium (ya tenemos vía MINA) → puedes entrar al Linux via SSH desde tu laptop con `ssh user@phone` y obtienes un bash directo en el distro.
   - SFTP server (ya) montado en el rootfs → puedes mover archivos con FileZilla directamente.
   - HTTP server (ya) → web shell en navegador.
   - Vault cifrado → el distro lo monta como `/elysium/vault` decrypted-on-access.
   - Time-travel → puedes hacer `cd /elysium/time-travel/2025-Q1` y obtener bash del estado de esa fecha.

### Implementación técnica

**Stack mínimo viable:**

| Componente | Fuente | Tamaño | Licencia |
|---|---|---:|---|
| Terminal emulator | Vendoring de Termux `terminal-view` + `terminal-emulator` (Apache 2.0) | ~2 MB | ✅ Apache 2.0 |
| PTY backend | `proot` + wrapper Java | ~3 MB | ✅ GPLv3 (compatible con dynamic linking via JNI) |
| Distros rootfs | Descargados on-demand desde mirrors oficiales | 50-300 MB cada uno | ✅ |
| VNC client | `libvncclient` JNI wrapper | ~2 MB | ✅ GPLv2 |
| X11 server opcional | `tigervnc` server dentro del distro | 5 MB | ✅ GPLv2 |
| SSH client (forward X11) | Ya tenemos Apache MINA SSHD — añadir canal `x11-req` | +0.5 MB | ✅ Apache 2.0 |
| Filesystem bridge (proot mounts) | `proot` `-b` flag | nativo | — |

**Sin root requeridos:** todo corre con `proot`. Para GUI acelerado se puede opcionalmente correr
Xwayland vía root si está disponible, pero por default es VNC-over-TCP local.

**Arquitectura interna:**

```kotlin
// core/runtime/terminal/
RuntimeOrchestrator     // maneja ciclo de vida de sesiones/snapshots
TerminalService        // foreground service que mantiene la PTY viva
TerminalSession        // 1 sesión = 1 proceso + 1 stream
TerminalEmulator       // ANSI / state machine (de Termux)
TerminalView           // Compose wrapper del TerminalView nativo

// core/runtime/distros/
DistroCatalog          // Debian / Ubuntu / Alpine / Arch / Kali / etc.
DistroInstaller        // descarga mirror + extract + bind mount setup
DistroSnapshot         // rootfs snapshot docker-layer-style
DistroLauncher         // lanza `proot -0 -r <rootfs> -b ... -w / <cmd>`

// core/runtime/bridge/
FilesystemBridge       // mapea SAF/vault/time-travel/cloud a mounts proot
MountResolver          // resuelve /elysium/* → paths reales

// core/runtime/gui/
VncClientComposable    // cliente VNC embebido en Compose
X11ForwardChannel      // reusa Apache MINA para tunnel X11 sobre SSH
WaylandForwarder       // fase 2, opcional

// core/runtime/ssh/
SshX11Channel          // SSH client con X11 forwarding
SshShellChannel        // SSH client con shell directo
```

**API Android que vamos a usar:**
- `Runtime.exec()` con `ProcessBuilder` env vars.
- `LocalSocket` para IPC entre `TerminalService` y `TerminalView`.
- `FileDescriptor` para PTY (vía proot).
- `Chroot`-equivalent: `proot`.
- `Vibrator` para haptic feedback en teclas.

### Por qué esto vuelve a Elysium EL MEJOR

**No existe** en el mundo:
- Un file manager con terminal integrado.
- Un file manager que instale distros de Linux.
- Un file manager que monte cloud + vault como filesystem dentro del rootfs de un distro.
- Un file manager con time-travel visible desde bash (`cd` a 2024-01-15).
- Un file manager que ejecute apps GUI Linux en ventanas Android nativas.

Los proyectos existentes (Termux, iSH, Andronix, LinuxDeploy, UserLAnd, AnLinux) son shells
o launchers — **no son file managers**. Elysium es el primero que toma el **file manager como
sistema central** y le pega un Linux runtime debajo.

### Fase de implementación sugerida

| Sub-fase | Scope | Esfuerzo |
|---|---|---:|
| 9.6.1 | Vendor Termux `terminal-view` + `terminal-emulator` + Compose wrapper | 1 sem |
| 9.6.2 | `TerminalService` foreground con PTY + LocalSocket IPC | 1 sem |
| 9.6.3 | Catálogo de distros + installer + launcher (`proot` wrapper) | 2 sem |
| 9.6.4 | Filesystem bridge: SAF/vault/time-travel/cloud mounts | 2 sem |
| 9.6.5 | VNC client embebido en Compose (X11 apps) | 2 sem |
| 9.6.6 | SSH client + X11-forwarding tunnel (reusa MINA SSHD) | 1 sem |
| 9.6.7 | Snapshot layers para distros | 1 sem |
| 9.6.8 | Bash auto-completion + tmate/tmux integration | 1 sem |
| 9.6.9 | App launcher: detecta GUI apps instaladas, las abre en ventana Compose | 1 sem |

**Total:** ~10-12 semanas para el runtime completo. **Pero** ya en la semana 4 se puede
tener un terminal + Debian corriendo — eso solo es reason enough para instalar Elysium.

### Demo del primer uso

```
1. User installs Elysium Vanguard.
2. Abre app → ve dashboard con sus archivos (vault, time-travel, Drive, etc).
3. Tap "Runtime" en sidebar.
4. Ve catálogo: [Debian stable] [Ubuntu 24.04] [Alpine latest] [Arch] [+ Add custom]
5. Tap Debian → download 80 MB → extract → listo en 2 min.
6. Tap "Open Terminal" → ves bash de Debian corriendo dentro de Elysium.
7. Escribe: `ls /sdcard` → ve todos sus archivos Android.
8. Escribe: `ls /elysium/vault` → ve sus archivos cifrados DECIFRADOS, on-demand.
9. Escribe: `cd /elysium/time-travel/2025-Q1 && ls -la` → ve el filesystem como estaba en Q1 2025.
10. Escribe: `apt install gimp && gimp &` → GIMP se abre en una ventana Compose encima del terminal.
11. GIMP puede "abrir archivo" → ve el filesystem completo de Elysium.
12. Puede guardar sobre /sdcard o /elysium/vault directamente.
13. Desde la laptop, `ssh elysium@phone` → obtienes el mismo bash con el mismo filesystem.
14. Pasas un archivo de la laptop al phone con `scp` → aparece en /sdcard del distro.
15. Cierras GIMP, vuelves al modo file manager → todo el trabajo se ve.
```

**Eso, amigo Jor, no existe hoy.**

---

## Roadmap ejecución re-priorizado

```
Phase 9.1  — Universal protocol matrix + cloud           6-8 sem
Phase 9.2  — Cross-platform native apps                   8-10 sem
Phase 9.3  — AI layer (semantic + LLM + translation)      6-8 sem
Phase 9.4  — Time-travel + P2P mesh                       4-6 sem
Phase 9.5  — Legendary (CRDT/PQ-crypto/AR/VR/DID/DRM)     8-10 sem
⭐ Phase 9.6 — Sovereign Linux Runtime                    10-12 sem
Phase 9.5+ — Companions (Wear/Auto/TV) + quick wins       ongoing
```

**Phase 9.6 es la nueva estrella.** Sin runtime, Elysium es el mejor file manager del mundo.
Con runtime, Elysium es **el primer sistema operativo de bolsillo soberano del mundo**.

---

## Tech stack unlocked (sin preocuparnos por tamaño)

**Heavy things que vuelven al ring:**

- `ffmpeg-kit-full` (~50MB): vuelve para conversión de video/audio profesional.
- Whisper.cpp Android (~30MB) para STT universal.
- NLLB-200 quantized (~250MB) para traducción universal.
- Phi-3-mini Q4 (~1.8GB) para LLM agent.
- Yjs/Automerge Kotlin port (~2MB) para CRDT.
- Post-quantum libs: Bouncy Castle PQC + liboqs.
- IPFS lite client (~5MB).
- BitTorrent lib: torrent4j (~2MB).
- ⭐ **Termux core libraries vendored (~3MB)**.
- ⭐ **proot binary + GLIBC shim (~3MB)**.
- ⭐ **libvncclient JNI (~2MB)**.
- ⭐ **tigervnc X11 server en rootfs de cada distro (~5MB pero on-demand)**.

**Bundle total estimado (Phase 9.6 incluido):** ~4-5 GB.
**Lo que dijiste:** que valga.

---

## Branding

Elysium Vanguard + tagline **"The Sovereign Runtime for the Planet"**.

Sub-marcas dentro de la misma app:
- **Elysium Files** — el file manager original.
- **Elysium Net** — protocol matrix + P2P mesh.
- **Elysium Vault** — cifrado post-quantum.
- **Elysium AI** — semantic search + LLM agent.
- **⭐ Elysium Shell** — el terminal + distros.
- **Elysium Cloud** — todos los cloud providers, unificados.
- **Elysium Time** — versioning time-travel.
- **Elysium Space** — AR/VR spatial UI.

Todo bajo una sola app, una sola UI, un solo installer.

---

## Cómo arrancamos

Mi recomendación:

**Phase 9.6 primero.**

¿Por qué?

1. **Porque cambia las reglas del juego.** File manager + Terminal + Linux + GUI apps = nadie tiene esto. Si arrancamos por los protocols, llegamos a "el mejor FM del mundo". Si arrancamos por runtime, llegamos a "lo que ningún teléfono ha tenido hasta hoy".

2. **Es progresivo.** Semana 1 ya hay terminal. Semana 4 ya hay Debian instalable. Semana 8 ya hay apps GUI. La revelación pasa en pasos visibles, no en un solo gran reveal.

3. **Aprovecha TODO lo que ya tenemos.** SFTP server, HTTP server, vault, time-travel scaffolding, AI layer — todo se monta encima de un Linux runtime y de pronto tienes bash en vault cifrado, bash en time-travel, ssh-into-phone.

4. **Es la mejor historia de marketing del planeta.** "El primer teléfono con Linux real" es portada en HN, Reddit, The Verge. Es lo que la gente recuerda.

Si querés arrancar por Phase 9.1 (protocol matrix) por preferencia, lo entiendo — pero **mi pick es Phase 9.6**.

¿Arrancamos 9.6?
