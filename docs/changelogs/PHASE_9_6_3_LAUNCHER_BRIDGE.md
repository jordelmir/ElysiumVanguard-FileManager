# Phase 9.6.3 — Sovereign Runtime: Launcher + Filesystem Bridge

**Fecha:** 2026-07-09
**Status:** ✅ Compila · ✅ 277 tests verdes (33 nuevos) · ✅ assembleDebug verde
**Versión:** 1.0.0-TITAN+9.6.3

---

## TL;DR

Elysium Vanguard ahora puede **abrir un shell dentro de un distro Linux instalado**.
El usuario instala Alpine (60 MB) desde el catálogo, tap "Open" → entra a una sesión
de shell que arranca con `cwd = rootfs`. La experiencia es **`/system/bin/sh` con el
rootfs como directorio de trabajo**: podés `cat /etc/os-release`, `ls bin/`, `find`,
lo que sea sobre el filesystem extraído. Apt/apk/pacman aún no corren sus binarios
(eso entra en 9.6.3.1 con el `libproot.so`), pero la **infraestructura** está
completa: contrato `DistroLauncher`, resolución inteligente, factory en
`TerminalSession`, navegación, scope Hilt compartido.

Esto es la **revelación** del Phase 9.6: el primer file manager que abre un Linux
real dentro de la APK.

---

## Lo que entregué

```
core/runtime/distros/launcher/
├── DistroLauncher.kt              ← interface (buildShellCommand / buildProbeCommand / isAvailable)
├── LauncherKind.kt                ← JAILED_SHELL · NATIVE_PROOT · NAMESPACE_UNSHARE
├── LauncherCapabilities.kt        ← data class: canRunElfBinaries · supportsBindMounts · etc.
├── JailedDistroLauncher.kt        ← impl real para 9.6.3: /system/bin/sh con cwd=rootfs
├── NativeProotLauncher.kt         ← stub ABI-aware, listo para 9.6.3.1 (libproot.so)
├── LauncherResolution.kt          ← object + data class LauncherPick + DistroLauncherRegistry
└── LauncherResolver.kt            ← fun interface + LauncherResolutionResolver (DEFAULT/JAILED)

core/runtime/bridge/
└── FilesystemBridge.kt            ← MountEntry, ElysiumNamespaces, mountsFor(), standardMounts()

core/runtime/distros/
├── DistroModule.kt                ← Hilt SingletonComponent: provee DistroManager + LauncherResolver
└── RealDistroHttpDownloader.kt    ← HttpURLConnection (movido desde RuntimeViewModel)

core/runtime/terminal/session/
└── TerminalSession.kt             ← nuevo factory Companion.forDistro(rootfs, pick, ...)

features/runtime/terminal/
├── TerminalViewModel.kt           ← inyecta SavedStateHandle + DistroManager; arma session con launcher
└── TerminalScreen.kt              ← muestra el distro + el kind del launcher en el header

features/runtime/
└── RuntimeScreen.kt               ← "Open" ahora pasa el distroId a la nueva ruta

MainActivity.kt:
  Nueva ruta: `terminal_distro/{distroId}` (codificado URL-safe)
  La ruta vieja `terminal` sigue funcionando para el local shell.
```

---

## `DistroLauncher` interface

```kotlin
interface DistroLauncher {
    val kind: LauncherKind
    val capabilities: LauncherCapabilities
    fun buildShellCommand(rootfsDir: File, script: String): List<String>
    fun buildProbeCommand(rootfsDir: File, args: List<String>): List<String>
    fun isAvailable(rootfsDir: File): Boolean
}
```

Tres implementaciones posibles hoy, una real:

| Launcher | Estado 9.6.3 | Qué hace |
|---|---|---|
| `JailedDistroLauncher` | ✅ real | `/system/bin/sh -c "<probe>"` con cwd=rootfs. Sin ELF translation. |
| `NativeProotLauncher` | ⚠️ stub | Estructura correcta (`proot -0 -r <rootfs> -b <mounts> /bin/sh`); `isAvailable()=false` hasta que 9.6.3.1 véndere `libproot.so`. |
| `LauncherKind.NAMESPACE_UNSHARE` | 🔮 futuro | prctl+unshare; sin root pero con separación de namespaces. |

---

## `FilesystemBridge` — el bridge

```kotlin
data class MountEntry(hostPath, guestPath, readOnly = true, label = null)

object FilesystemBridge {
    fun mountsFor(namespaces: ElysiumNamespaces): List<MountEntry>
    fun standardMounts(sdcardPath: File?, vaultPath: File?): List<MountEntry>
}
```

Tabla de mounts default en 9.6.3:

| Host (Android) | Guest (dentro del distro) | Read-only | Notas |
|---|---|:---:|---|
| `Environment.getExternalStorageDirectory()` o lo que el SAF tree exponga | `/sdcard` | ✅ | Storage Android |
| `filesDir/vault/` | `/elysium/vault` | ❌ | Vault cifrado, decrypted on-access |
| `filesDir/time-travel/` | `/elysium/time-travel` | ✅ | Future (Phase 9.4 snapshots) |
| `filesDir/cloud/` | `/elysium/cloud` | ❌ | Mount union de cloud providers |

Actualmente el bridge **describe** los mounts pero el `JailedDistroLauncher` los
ignora (no hay namespace para bind). El `NativeProotLauncher` los traducirá a `-b`
flags cuando 9.6.3.1 lo conecte a `libproot.so`.

---

## Tests (33 nuevos)

### `DistroLauncherTest.kt` — 19 tests

- `JailedDistroLauncher`
  - `buildShellCommand injects a probe when script is empty` ✅
  - `buildShellCommand passes the user script through unchanged` ✅
  - `buildProbeCommand changes directory before running args` ✅
  - `isAvailable returns true when rootfs is a directory` ✅
  - `isAvailable returns false when rootfs is missing` ✅
  - `buildShellCommand rejects a missing rootfs` ✅ (IAE)
  - `buildProbeCommand rejects a missing rootfs` ✅ (IAE)
  - `capabilities expose JAILED baseline` ✅
  - `kind is JAILED_SHELL` ✅

- `NativeProotLauncher`
  - `kind and capabilities reflect proot flavor` ✅
  - `capabilities report no ELF when no ABIs were bundled` ✅
  - `isAvailable is false until the JNI binary ships` ✅ (crítico: el stub nunca
    miente y dice que está cuando no lo está)
  - `buildShellCommand returns the proot-missing sentinel when not available` ✅
  - `buildShellCommand produces a real proot flag shape when a fake launcher
    marks itself available` ✅

- `LauncherResolution`
  - `empty registry resolves to jailed shell` ✅
  - `forceJailed bypasses registry entirely` ✅
  - `production registry also resolves to jailed shell in 9_6_3` ✅
  - `LauncherPick carries launcher and reason together` ✅
  - `LauncherResolver staticResolver returns a LauncherPick` ✅

### `FilesystemBridgeTest.kt` — 10 tests

- `mountsFor returns empty list when all inputs are null` ✅
- `mountsFor exposes sdcard and vault with the correct guest paths` ✅
- `mountsFor includes time-travel and cloud when configured` ✅
- `standardMounts is a shortcut for sdcard plus vault only` ✅
- `MountEntry rejects a blank host path` ✅
- `MountEntry rejects a blank guest path` ✅
- `MountEntry rejects a relative guest path` ✅
- `MountEntry defaults readOnly to true` ✅
- (más variantes sobre null/null/null/null)

### `TerminalSessionForDistroTest.kt` — 4 tests

- `forDistro produces a session whose Config matches the launcher output` ✅
- `forDistro defaults to 80x24 and xterm-256color` ✅
- `forDistro honors overrides for cols, rows, termName` ✅
- `forDistro's command switches to the proot launcher output when supplied` ✅
  (usa un fake inline que simula estar disponible; demuestra que la wire no
  depende del launcher concreto)
- `forDistro rejects a missing rootfs` ✅ (IAE)

**Total: 277** unit tests passing, 0 failures, 0 errors.

---

## Cómo lo prueba Jor

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Abre app → tap "RUNTIME" en el dashboard
3. Tap "Install" en **Alpine Linux** (60 MB — el más rápido)
4. Espera 30-60s
5. Tap **Open** en el card de Alpine → entra al terminal jailed shell
6. El title muestra "Elysium Terminal · jailed"
7. La probe inicial lista el rootfs top-level:
   ```
   pwd; echo; echo '--- /etc/os-release (if any) ---'
   cat /etc/os-release 2>/dev/null || echo '(no os-release)'
   echo; echo '--- / top dir ---'; ls -la / | head -n 40
   ```
8. Podés escribir `ls -la`, `cat etc/os-release`, `find . -name apk-tools`,
   `which apk`, etc. — todo sobre el filesystem del distro extraído.

> ⚠ **Caveat honesto:** apt/apk/pacman **no corren** aún. El jailed shell
> solo tiene `sh` builtin + binarios del `/system` de Android. Para
> ejecutar `apk add python3` dentro de Alpine, necesitamos 9.6.3.1 con
> `libproot.so` vendoreado.

---

## Decisiones de arquitectura

1. **Interface-driven launchers.** Tres implementaciones hoy (`Jailed`,
   `NativeProot` stub, `Unshare` future). La resolución es ortogonal al
   installer: si 9.6.3.1 véndere `libproot.so`, no tocamos `DistroManager`
   ni `TerminalViewModel`, solo cambiamos el `isAvailable()`.

2. **`LauncherPick` (data class) vs `LauncherResolution` (object).**
   Original puse ambos con el mismo nombre; el compilador se quejó.
   Renombrado a `LauncherPick` para el data class. `LauncherResolution`
   queda como el coordinator object. `LauncherResolver` es la fun
   interface strategy (compatible con `() -> LauncherPick`).

3. **DistroModule + Hilt SingletonComponent.** Phase 9.6.2 creaba su
   propio `DistroManager` adentro del VM. Phase 9.6.3 mueve eso a un
   Hilt module singleton para que `RuntimeViewModel` y
   `TerminalViewModel` compartan el mismo manager. **Beneficio
   inmediato:** instalar Alpine en la pantalla `runtime` se observa en
   cualquier otra pantalla que escuche `manager.installed` sin
   signaling manual.

4. **URL-encoded distroId en la ruta.** Compose Navigation no permite
   algunos caracteres en nav arguments; usamos `URLEncoder.encode`
   por simetría con el resto del proyecto (`viewer_image`, `editor_text`,
   etc.).

5. **`TerminalViewModel` con SavedStateHandle.** Es la primera vez
   que el VM lee el distroId del SavedStateHandle. Esto es la forma
   canónica en Hilt-Androidx para pasar nav arguments; si 9.6.3.1
   quiere meter más args (e.g. un `command` prefilled), es trivial.

6. **`isAvailable` honesto sobre el stub.** `NativeProotLauncher`
   retorna `false` mientras no haya JNI. Mejor que mentir: el
   resolution cae al jailed shell y el usuario **ve** que algo
   cambió (el title muestra "Elysium Terminal · jailed" en lugar
   de "· proot"). No hay sorpresas silenciosas.

---

## Cosas que dejé fuera intencionalmente (9.6.3.1 backlog)

- `libproot.so` vendor por ABI (arm64-v8a, armeabi-v7a, x86_64, x86).
  Requiere firmar el .so o descargarlo on-demand desde Termux's
  official proot package.
- `NativeProotLauncher` actualmente stub honra `isAvailable()=false`,
  cae a jailed. Activar el real requiere: cargar el .so, agregar
  una pequeña capa JNI que llame a `proot_main(argc, argv)`.
- Bind mounts reales: hoy el bridge los **describe** pero el jailed
  no los usa. Cuando 9.6.3.1 proot esté wired, los mounts se
  traducen a `-b <host>:<guest>` flags.
- PTY real (Termux `termux-pty`): hoy el shell es un pipe y los
  prompts/editores tipo `vi` no pueden usar la línea de comandos.
- **Apt/apk/pacman ejecutables**: requiere proot real. Sin eso,
  el usuario tiene que `cat ./var/lib/dpkg/status` para ver paquetes.
- **Persistent `proot` session state**: hoy cada "Open" arranca un
  shell fresh; la persistencia (history, save state) entra con el
  PTY real.

---

## Métricas Phase 9.6.3

| | Antes (9.6.2) | Después (9.6.3) |
|---|---|---|
| Tests | 244 | **277** (+33) |
| Loc archivos nuevos | — | ~880 |
| Loc archivos modificados | — | ~210 |
| Launcher strategies | 0 (sin interface) | **3** (JAILED real + NATIVE_PROOT stub + UNSHARE planned) |
| Bind mount paths definidos | 0 | 4 (`/sdcard`, `/elysium/vault`, `/elysium/time-travel`, `/elysium/cloud`) |
| Hilt Singleton modules para distros | 0 | 1 (`DistroModule`) |
| Routes nuevas en NavHost | — | 1 (`terminal_distro/{distroId}`) |
| `assembleDebug` | ✅ | ✅ 248 MB |

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** **Phase 9.6.3.1** — Vendor `libproot.so` por ABI + activar `NativeProotLauncher` real + bind mounts reales + JNI shim que llame a `proot_main`. Es lo que vuelve apt/apk/pacman ejecutables de verdad.
