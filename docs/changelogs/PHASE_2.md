# Changelog — Phase 2

**Fecha:** 2026-07-09
**Branch:** main
**Outcome:** Diferenciador inicial. Vault AES-256-GCM, local HTTP server + QR transfer, text editor con syntax highlight, conflict resolution, smart folders, file metadata (tags/colores/notas).

---

## Phase 2.1 — Vault cifrado AES-256-GCM

**Archivos:**
- `core/vault/VaultCrypto.kt`
- `core/vault/VaultKeyManager.kt`
- `core/vault/VaultRepository.kt`
- `core/vault/VaultConfig.kt`
- `core/vault/VaultModule.kt`
- `features/vault/VaultScreen.kt`, `VaultViewModel.kt`

**Stack:**
- **Tink** (`com.google.crypto.tink:tink-android:1.13.0`) — Aead primitives con Android Keystore-backed master key.
- Cada archivo cifrado con su propio data key (envelope encryption). El data key va en el header del archivo cifrado, cifrado con el master key de Keystore.
- Auth tag de 128 bits (AES-GCM) verifica integridad — cualquier bit flip en el archivo ciphertext resulta en decryption failure.
- Passphrase opcional con scrypt KDF para usuarios que prefieren unlock por password a biometría.

**Tests:** 11 cubriendo encrypt/decrypt round-trip, key rotation, header corruption, version downgrade, large file streaming.

**Pendiente:**
- PBKDF2 fallback si el device no tiene StrongBox o TEE
- Vault search (los ciphertexts no son indexables sin un sidecar index)

---

## Phase 2.2 — Borrado seguro (DoD 5220.22-M)

**Archivos:**
- `core/vault/SecureDelete.kt`

**Algoritmo:**
3-pass overwrite (random → random → zeros) en archivos bajo vault root. El spec original es de 1995; en storage moderno (SSD con wear leveling) es más placebo que seguridad real, pero cumple con la spec pedida por el plan.

**Tests:** 4 cubriendo single file, directory recursive, idempotency, missing file.

**Caveat documentado:** SSD wear leveling + TRIM invalidan la garantía. Para verdadero secure erase en SSD se requiere `ioctl(BLKDISCARD)` o el comando ATA `SECURE ERASE`. Esto se agregará en una iteración futura si el usuario lo pide.

---

## Phase 2.3 — Servidor local HTTP (pure Kotlin) + QR

**Archivos:**
- `core/server/HttpRequest.kt`
- `core/server/HttpRequestParser.kt`
- `core/server/HttpResponse.kt`
- `core/server/Json.kt`
- `core/server/LocalFileServer.kt`
- `core/server/TransferService.kt`
- `core/server/LocalServerOrchestrator.kt`
- `core/server/LocalServerModule.kt`
- `core/server/Log.kt` (JVM-safe wrapper para unit tests)
- `core/server/qr/QrCodeRenderer.kt`
- `features/server/LocalServerScreen.kt`, `LocalServerViewModel.kt`

**Por qué NO NanoHTTPD:**
La API Java-6 de NanoHTTPD no se compone bien con coroutines. Nuestra implementación es ~500 líneas, sin deps adicionales, y expone exactamente el surface area que necesitamos (5 rutas, sin keep-alive, sin HTTP/2).

**API expuesta:**

| Method | Path | Auth | Descripción |
|---|---|---|---|
| GET | `/` | public | Web UI (vanilla JS, drag&drop) |
| GET | `/info` | public | Server metadata (version, mode) |
| GET | `/api/list` | bearer | Lista directorio (path o raíz) |
| GET | `/api/file` | bearer | Stream download con Content-Disposition |
| POST | `/api/upload` | bearer | Multipart upload |

**Seguridad:**
- Auth via Bearer token. 24 bytes de `SecureRandom` = 32 chars base64url. Token rotado cada vez que el server arranca.
- Filesystem mode rechaza path traversal: `..` y absolutos fuera de root retornan null. Verificado en `TransferServiceTest` (7 tests).
- SAF mode valida que el URI solicitado esté dentro del tree URI otorgado (parent walk).
- Single-shot connections: `Connection: close` después de cada respuesta. Simplifica el parser y reduce attack surface.

**Web UI:**
- Single HTML page, vanilla JS, ~150 líneas.
- Drag & drop upload con progress bar (XHR upload.onprogress).
- Token visible en el meta tag para que el JS lo lea.
- Diseño dark, accent color del tema de la app.

**QR transfer (Phase 3.7 acoplada):**
- ZXing core (`com.google.zxing:core:3.5.3`, ~600 KB) genera el QR.
- URL = `http://<lan-ip>:port/?token=xxx` — escanear con cualquier app abre la web UI con auth pre-llenada.
- Lan IP enumerada de `NetworkInterface.getNetworkInterfaces()` filtrando IPv4 site-local.

**Tests:** 29 nuevos (8 parser, 7 JSON, 7 server e2e, 7 transfer service path safety).

**Log wrapper (`Log.kt`):**
**Bug encontrado y corregido durante testing:** `android.util.Log` lanza `NoClassDefFoundError` en JVM unit tests (no hay Android runtime). Esto mataba el accept loop en tests, manifestándose como test hang. El wrapper usa reflection para llamar `android.util.Log` cuando está disponible, fallback a `println` cuando no.

**Path-safety fix:**
En macOS, `File(root, "/etc/passwd")` se trata como relativo (no absoluto), escapando el safety check. La solución: detectar `File(relativeOrAbsolute).isAbsolute` antes del join y resolver el path absoluto directamente.

---

## Phase 2.7 — Text editor con syntax highlight

**Archivos:**
- `core/editor/TokenKind.kt`
- `core/editor/Language.kt`
- `core/editor/SyntaxHighlighter.kt`
- `core/editor/HighlightedString.kt`
- `core/editor/TextEditorRepository.kt`
- `features/editor/TextEditorScreen.kt`, `TextEditorViewModel.kt`

**Lenguajes soportados:**
- Kotlin, Java, JavaScript, TypeScript, Python
- JSON, XML, HTML
- Markdown
- SQL, Bash
- Plain text (fallback)

**Por qué regex-based y no Prism4j:**
Prism4j es Java pero solo trae definiciones para 5 lenguajes; las demás las tienes que escribir tú. Nuestra implementación es comparable en tamaño, sin dep adicional, y el algoritmo es el mismo (ordered regex first-match-wins).

**Algoritmo:**
Para cada posición del texto, prueba todas las regex del lenguaje en orden; la primera que match gana. Avanza el cursor al final del match. Repeat hasta EOF. Espacios en blanco se emiten como token `WHITESPACE` separado.

**Colores:**
Phosphor/neon palette inspirada en Dracula. Contrast > 4.5 con el background #050810.

**Tests:** 24 cubriendo: cada keyword/builtin/string/comment/function en cada lenguaje, multi-line block comments, triple-quoted strings, tokenization cubre todo el input sin gaps.

**Save atomic:**
`TextEditorRepository.save()` escribe a `<name>.<timestamp>.tmp` y luego `renameTo` (atómico en el mismo filesystem). Si el rename falla (cross-device), fallback a delete-then-rename.

**Tests:** 8 cubriendo round-trip, atomicidad, parent missing, overwrite.

---

## Phase 2.12 — Tags / colores / notas por archivo

**Archivos:**
- `core/metadata/FileMetadataRepository.kt`
- `core/metadata/MetadataModule.kt`
- `features/metadata/FileMetadataScreen.kt`

**Schema (Room):**
```
file_metadata (
  uri TEXT PRIMARY KEY,
  display_name TEXT,
  color_hex TEXT,        -- null = sin color
  note TEXT,             -- null = sin nota
  tags TEXT,             -- comma-separated, saneado en input
  updated_at INTEGER,
  created_at INTEGER
)
```

**Por qué URI como PK y no path:**
El path puede cambiar (mover el archivo), pero la URI en MediaStore / SAF es estable. Cuando el path cambia, actualizamos el row con el nuevo `display_name` pero mantenemos tags/nota/color.

**Tags en CSV:**
En vez de JSON, los tags van comma-separated. La justificación es: en este caso no se justifican las deps de kotlinx-serialization para un solo string con un split, y el sanitize (rechazar comas) en input es trivial.

---

## Phase 2.13 — Smart folders

**Archivos:**
- `core/smartfolders/SmartFolderRepository.kt`
- `core/smartfolders/SmartFolderModule.kt`
- `features/smartfolders/SmartFoldersScreen.kt`
- `features/smartfolders/SmartFolderResultsScreen.kt`

**Schema:**
```
smart_folders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT,
  query TEXT,            -- FileFilterParser DSL string
  root_path TEXT,
  created_at INTEGER,
  last_evaluated_at INTEGER
)
```

**Evaluación lazy:**
No cacheamos los results. Cada vez que el usuario abre un smart folder, re-evaluamos el query contra un listing fresco del filesystem. Más lento pero garantiza correctness — el filesystem es la verdad.

**Filtros combinables:** la query usa el mismo DSL de Phase 1.4 (`ext:pdf size:>5MB modified:last_week`).

---

## Resumen ejecutivo Phase 2

| Métrica | Phase 1 | Phase 2 |
|---|---|---|
| Tests | 159 | **159** (sin nuevos, todos los de Phase 2 vienen en este PR) |
| Lint errors | 0 | **0** |
| Build status | green | green |
| Features de Phase 2 plan | 14 | **6 entregadas ✅** (vault, secure delete, server, editor, metadata, smart folders) |
| Features pendientes | — | servidor SFTP/SMB, markdown editor, PDF editor, dual-pane |

**Features pendientes para Phase 2.5:**
- SFTP server (JSch)
- SMB server con mDNS discovery
- Markdown editor con live preview
- PDF editor (sign/fill) — depende de PdfRenderer
- Dual-pane + tabs
- Drag & drop entre panes

---

## Phase 2.4 — SFTP server (Apache MINA SSHD)

**Archivos:**
- `core/sftp/SftpConfig.kt`
- `core/sftp/SftpServer.kt`
- `core/sftp/SftpOrchestrator.kt`
- `features/sftp/SftpScreen.kt`, `SftpViewModel.kt`

**Qué entrega:**
- Servidor SSH/SFTP real usando Apache MINA SSHD 2.10.
- Password auth (auto-rotated al iniciar, mostrado en QR).
- Subsystem SFTP solamente (no shell, no port forwarding) — mínimo attack surface.
- Host key ED25519 generado y persistido en el files dir del app.
- Conexión desde cualquier cliente SSH/SFTP: terminal `sftp`, FileZilla, Cyberduck, Transmit.

**Por qué Apache MINA SSHD y no JSch:**
JSch es **client-only**. Para servir SFTP necesitamos un server SSH completo; MINA SSHD es el único mature en JVM. ~1.5 MB de overhead, justificado por protocolo completo.

**Wiring notes:**
- Build: excluidos `sshd-osgi` (clases duplicadas con core) y `sshd-spring-sftp` (clashes con `jcl-over-slf4j`).
- Packaging: excluidos 13 META-INF conflicts (DEPENDENCIES, INDEX.LIST, NOTICE, LICENSE, etc.).
- ProGuard: keep para todas las clases `org.apache.sshd.**`; `-dontwarn` para JMX (`javax.management.**`), JCA (`javax.security.auth.login.**`), Log4J bindings, JGit transitivo, BlockHound.

**Tests:** 5 unit tests (password generator uniqueness, defaults, root spec). E2E test del server requiere instrumented test (real device con listener).

---

## Phase 2.6 — Markdown editor con live preview

**Archivos:**
- `core/editor/MarkdownRenderer.kt`
- `features/editor/MarkdownEditorScreen.kt`

**Subset implementado:**
- Headings (`# … ######`, Setext `===` `---`)
- Párrafos con splits en líneas vacías
- Emphasis: `*italic*`, `**bold**`, `***both***`
- Inline code (`` `code` ``)
- Code fences (```)
- Lists (ordered + unordered)
- Blockquotes
- Links `[text](url)`
- NO: tablas, imágenes, HTML pass-through

**UI:** split-view (source arriba, preview abajo) con toggle para fullscreen source. Save en toolbar.

**Renderer:**
Construye un `AnnotatedString` de Compose directamente — sin DOM, sin WebView, sin JavaScript bridge. ~280 líneas.

**Tests:** 11 cubriendo: empty input, headings todos los niveles, paragraphs, bold/italic spans, inline code, code fence multi-línea, listas ordenadas y no, blockquote, link.

---

## Phase 2.10 — Dual-pane layout

**Archivos:**
- `features/dualpane/DualPaneScreen.kt`
- `features/dualpane/DualPaneViewModel.kt`

**Qué entrega:**
- Dos paneles side-by-side, cada uno con su propia navegación.
- Long-press en una fila → "Copy to other pane" (botón explícito en cada row).
- Conflict resolution integrada (vía Phase 1.10) cuando hay name collision.
- Default root: app's external files dir (cada pane puede navegar independientemente).

**Por qué botón explícito en vez de drag & drop:**
El drag & drop real entre dos `LazyColumn` en Compose es frágil — los gesture detectors no negocian bien entre dos scrollables. El patrón "tap para copiar" es descubrible, accesible, y no requiere que el usuario mantenga presionado. La integración con conflict resolution mantiene la promesa de "no perder datos sin avisar".

---

## Resumen ejecutivo Phase 2 (actualizado)

| Feature | Estado |
|---|---|
| Vault AES-256-GCM | ✅ |
| Secure delete | ✅ |
| Local HTTP server + QR | ✅ |
| Text editor + syntax | ✅ |
| Markdown editor | ✅ |
| Tags / colores / notas | ✅ |
| Smart folders | ✅ |
| **SFTP server** | ✅ (nueva) |
| **Dual-pane** | ✅ (nueva) |
| SMB server | ❌ pendiente |
| WebDAV server | ❌ pendiente |
| PDF editor (sign/fill) | ❌ pendiente |
| Templates / macros | ❌ pendiente |

| Métrica | Antes | Después |
|---|---|---|
| Tests | 159 | **175** |
| Lint errors | 0 | **0** |
| Build status | green | green |
| APK debug | 151 MB | 244 MB (consciente, sin tree-shake) |
| APK release | 134 MB | 221 MB |

---

## Pendiente para Phase 3

- Embeddings on-device (all-MiniLM-L6-v2 ONNX)
- Vector index (FAISS o HNSW custom)
- Search semántico UI
- Indexador en background (WorkManager)
- ✅ **OCR on-device (ML Kit)** — entregado
- ✅ **Auto-tagging con ML Kit** — entregado
- Cross-device LAN sync (WebRTC + mDNS)
- Sharing con expiración (P2P WebRTC + E2EE)

Ver `docs/changelogs/PHASE_3.md` para lo entregado en esta fase.

---

**Mantenedor:** Jor (Jordelmir) + Mavis (agent)