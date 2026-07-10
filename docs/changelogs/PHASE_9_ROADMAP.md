# Phase 9 — Roadmap "El Mejor Administrador de Archivos de la Historia Humana"

**Fecha:** 2026-07-09
**Decisión de Jor:** Olvidamos preocupaciones de peso. Vamos a por todo.
**Mandato:** Convertir Elysium Vanguard en el FM más capaz del planeta.
**Filosofía:** Velocidad de features > anything else. Si una feature mejora el producto, se mete.

---

## Lo que ya tenemos y dónde estamos parados

Elysium Vanguard es una bestia infrautilizada. Tiene más capacidades técnicas que el 95% de los FM
de Play Store, pero la superficie UI no las expone todas y faltan features de 2026 esperadas.

**Inventario de poderes actuales:**
- ✅ SAF-first dual-mode (DocumentFile + File API)
- ✅ Vault AES-256-GCM con Tink + Android Keystore
- ✅ SFTP server (Apache MINA SSHD)
- ✅ HTTP server + QR transfer
- ✅ Music Hub con player + EQ
- ✅ Document viewer (PDF/DOCX/TXT) + Image viewer
- ✅ OCR on-device (ML Kit text-recognition)
- ✅ Auto-tagging ML (ML Kit image-labeling)
- ✅ Smart folders DSL
- ✅ Storage analyzer treemap
- ✅ Duplicates detector SHA-256
- ✅ Batch rename engine (6 tokens)
- ✅ Fuzzy search + content search BM25
- ✅ Markdown editor + syntax-highlight text editor
- ✅ Tags/colores/notas (Room)
- ✅ Dual-pane layout
- ✅ Conflict resolution + Operation queue con ETA
- ✅ Trash con auto-purge (Phase 8)
- ✅ Compression service + ZIP engine con bomb protection
- ✅ Privacy policy + secure delete
- ✅ Semantic search scaffolding (MediaPipe)

**Lo que nos falta para ser EL MEJOR:**
- ❌ Cloud providers (Drive/Dropbox/OneDrive) — tabla-stakes 2026
- ❌ Network protocols (SMB/WebDAV/FTP client+server)
- ❌ Archive formats: RAR / 7z / TAR / BZ2
- ❌ PDF editor lite (sign/fill/annotate)
- ❌ Material You dynamic theming
- ❌ Semantic search on-device real (no scaffolding)
- ❌ Time-travel versioning (git-like filesystem)
- ❌ Cross-device LAN sync (LocalSend-killer con E2EE)
- ❌ On-device LLM agent con function calling
- ❌ Voice commands
- ❌ AR file finder (CLIP-based)
- ❌ Wear OS / Android Auto companions
- ❌ Universal share-sheet integration
- ❌ Image operations UI (resize/convert/EXIF)
- ❌ Smart rename con EXIF
- ❌ Universal clipboard sync
- ❌ Recents widget
- ❌ Perceptual hash (casi-duplicados)
- ❌ File comparison tool (diff/hash)
- ❌ Per-folder color/icon
- ❌ Collaborative annotations
- ❌ Smart folders auto-refresh
- ❌ 3D-pie storage visualizer

---

## Phase 9.1 — Brecha competitiva cerrada (3-4 semanas)

Alinearse con lo que el power user espera en 2026.

### 9.1.1 Network Protocols Stack

**Por qué:** Sin SMB/WebDAV/FTP no somos opción para el power user. Solid, MiXplorer y X-plore lo tienen.

| Sub-feature | Dep | Esfuerzo |
|---|---|---:|
| SMB client (browse + read/write) | smbj 0.11.0 (~2MB) | 1 sem |
| WebDAV client + server | OkHttp + custom XML / Apache Slide | 1 sem |
| FTP server | Apache FtpServer ~3MB | 3 d |
| FTP client | OkHttp + custom parser | 2 d |
| mDNS discovery (LAN auto-find) | jmDNS 3.5.8 | 2 d |
| Network bookmark manager | Room extension | 1 d |

**UX:** Menú "Network" con tabs Servers (que corro) / Clients (que conecto) / Bookmarks. Quick-add con QR-code-from-other-device.

### 9.1.2 Cloud Providers (primera clase)

**Por qué:** El 70% de los usuarios tienen archivos en Drive. Sin esto, nos quedamos local-only.

| Provider | Dep | Spec |
|---|---|---|
| Google Drive | google-api-services-drive ~5MB | OAuth2 + virtual tree sobre `FileManagerRepositoryDual` |
| Dropbox | dropbox-core-sdk ~1MB | OAuth2 + virtual tree |
| OneDrive | microsoft-graph ~3MB | MSAL + virtual tree |
| Box | box-java-sdk ~2MB | OAuth2 + virtual tree |
| S3-compatible | aws-sdk-android ~6MB | Configurable para Backblaze/Wasabi/etc |
| WebDAV-as-cloud | ya incluido en 9.1.1 | Nextcloud, ownCloud, Koofr |

**Spec clave:** cada provider implementa `RemoteRepository` (interfaz nueva con `listChildren()`, `openInput()`, `openOutput()`, etc.). `FileManagerRepositoryDual` los wrappea idéntico a SAF. UI idéntica, el usuario solo ve un folder más.

### 9.1.3 Archive formats universales

**Por qué:** Zip-only es 2005.

- Apache Commons Compress 1.26.0 (~3MB): 7z, TAR, BZ2, GZ, XZ, Dump.
- Junrar: RAR unpack (no create).
- Create RAR no es gratis (license), pero unpack sí.
- UI: "Extract here" / "Create archive" en menú contextual. Auto-detect format por magic bytes.

### 9.1.4 PDF editor lite

**Por qué:** Acrobat cuesta $20/mes y pesa 500MB. El 80% de los casos son: firmar, llenar form, anotar, unir/dividir.

- PdfRenderer (AOSP) para visualizar.
- Compose Canvas para overlays: signature pad, text input, highlight, sticky notes.
- Guardar: re-encodear bitmap con overlays → PDF simple.
- Form fields: `pdfbox-android` (~6MB, opcional on-demand) para los forms complejos.
- Split/merge: pdfbox-android.

### 9.1.5 Image operations UI sobre ConversionEngine

**Por qué:** ConversionEngine ya existe. Solo falta cara.

- Selección batch de N imágenes en grid.
- Cola integrada con OperationQueue existente.
- Resize (%), aspect-ratio lock, presets Instagram/Twitter.
- Convert JPEG ↔ PNG ↔ WebP ↔ HEIC.
- Strip EXIF (privacy toggle).
- Compress quality slider.
- Rotate 90/+45/-45.

### 9.1.6 Material You + Accessibility

**Por qué:** UX expectation 2026.

- Dynamic color API 31+.
- Monetón fallback para API <31.
- Custom palettes (Titan dark / light / solar / void).
- TalkBack audit completo, contentDescription en cada ítem de file list.
- Switch Access + font scaling 200%.

### 9.1.7 Share sheet first-class

**Por qué:** Cada "Save to..." desde otra app es un usuario nuevo.

- Activity con `ACTION_SEND_MULTIPLE` + `ACTION_VIEW` content URIs.
- Elegir folder destino con picker tree-native.
- Pre-create vault-protected folder si es personal.
- Quick actions: "move to favorites", "compress before save".

---

## Phase 9.2 — AI-native layer (4-6 semanas)

Donde sacamos distancia del resto. Esto es la capa de inteligencia que ningún FM en Play Store
tiene on-device.

### 9.2.1 On-device semantic search real

**Spec:** El scaffolding de Phase 3 con MediaPipe necesita reescribirse con ONNX.

- Modelo: `all-MiniLM-L6-v2` cuantizado (`int8`, ~25MB) hosted en GitHub Releases.
- Embedding extractor: ONNX Runtime Mobile (~10MB).
- Index: HNSW custom en Kotlin (`HnswIndex.kt`), no FAISS (FAISS-android pesa 30MB).
- Pipeline:
  1. WorkManager (charging + Wi-Fi) indexa en background.
  2. Pre-procesa: PDF → pdftotext, imágenes → OCR (reusar), audio → transcripts (Whisper-tiny), código → tal cual.
  3. Chunk a 256-token, embed, write a SQLite (vector blob) + HNSW.
  4. Query: rank top-K en <200ms para 100K chunks.
- UI: nuevo tab "🧠 Semantic" en SearchScreen. Query en lenguaje natural: "el contrato de marzo", "fotos de la playa".

### 9.2.2 Auto-tagging evolution

- Más allá de labels: detecta entidades (personas con face embeddings, eventos, fechas, montos en recibos).
- People clustering: FaceNet embeddings → cluster por persona → "fotos de María".
- Receipt parsing: detectar total, items, fecha → tag automático como "receipts:2024-03".

### 9.2.3 Smart folders como sistema reactivo

- Auto-refresh: WorkManager reindexa cuando hay cambios (FileObserver).
- Smart folders como "rules engine" tipo IFTTT: "if size > 100MB and modified > 30d → move to /Large Archive".
- Push updates cuando un archivo cumple una regla.

---

## Phase 9.3 — Time-travel + Cross-device (4-6 semanas)

Funcionalidades que no existen en ningún FM. Esto es lo que pasa cuando tienes un repositorio
local-first con vault.

### 9.3.1 Time-travel versioning (git-like)

**Visión:** Cada cambio importante crea un snapshot. Puedes mover un slider y ver el filesystem
en cualquier punto del pasado. Restaurar cualquier versión.

- Snapshot engine: COW filesystem-style. Para SAF folders: registrar checksums + paths en SQLite (no podemos COW reales). Para File-API folders: hardlinks/symlinks si es posible.
- Metadata snapshot en SQLite: `snapshots(id, timestamp, parent_id, op_type, path, hash)`.
- UI: slider temporal en una barra superior. Tap en cualquier punto = preview del filesystem.
- Restore: copia el archivo de vuelta desde snapshot (storage cost).
- **Lifetime:** configurable 30d / 90d / 1y / forever.

### 9.3.2 Cross-device LAN sync (LocalSend-killer)

**Visión:** Sincronizar entre devices en LAN sin servidor, con E2EE.

- Discovery: mDNS broadcast (`_elysium._tcp`).
- Transport: WebRTC DataChannel (peer-to-peer, no server).
- Encryption: libsodium / Tink AES-GCM con key derivada de QR-scan-pair.
- Bidirectional: phone ↔ tablet ↔ laptop (si instalamos desktop app).
- Selective: solo folders marcados como "synced".

### 9.3.3 Universal clipboard

- Junto con 9.3.2: texto, imágenes, archivos <10MB viajan entre devices.
- "Copied on phone" → "Pasted on tablet".
- Visual indicator: 🔗 en status bar cuando hay sync.

### 9.3.4 Recent files sync

- Últimos abiertos en cualquier device aparece en todos.
- Cross-device "pick up where you left off".

---

## Phase 9.4 — Legendary tier (6-8 semanas)

Esto es lo que vuelve a Elysium legendary. Otros FM no van a poder copiar esto en años.

### 9.4.1 Phi-3-mini Q4 on-device LLM

**Visión:** "Organiza mis Downloads por tipo de documento y renombra con la fecha".

- Modelo: Phi-3-mini-4k-instruct Q4_K_M (~1.8GB). Download on-demand desde GitHub Releases en primer uso.
- Runtime: llama.cpp Android port (custom build, ~3MB lib).
- Function calling: capa de tools tipados — `move_file`, `rename`, `create_folder`, `find`, `tag`.
- Sandbox: el LLM **propone** una lista de acciones; user aprueba con un tap antes de ejecutar.
- Memoria: historiales de acciones para re-aprender del usuario.

### 9.4.2 Conversational UI

- Bottom sheet flotante 🤖 en cualquier screen.
- Texto o voz (Whisper-tiny STT).
- Multi-turn context: "esa" / "el otro" / "el último que abriste".

### 9.4.3 Voice commands

- Vosk STT (~10MB modelo español, ~50MB inglés).
- Hotword detection: "Hey Elysium".
- Intent parser custom: "abre mis contratos de 2024", "comprime los PDFs de Downloads".

### 9.4.4 AR file finder

- Geolocalización + CLIP embeddings.
- "Find the receipt from the cafe I went to yesterday".
- Indoor location tags (bluetooth beacon pairing opcional).

---

## Phase 9.5 — Compañas + Polish (ongoing)

### 9.5.1 Wear OS

- Quick access: recents, favorites, vault unlock.
- Voice command al watch para buscar en phone.
- Notification streaming: trash auto-purge alert.

### 9.5.2 Android Auto (passenger mode)

- Browse music/podcasts mientras conduces.
- Voice commands only.
- Read-it-later: podcast-style TTS de documentos.

### 9.5.3 Android TV leanback

- Browse 4K videos en TV.
- Cast desde local server (ya existente).

### 9.5.4 Chrome OS / WebUI

- Companion web UI al HTTP server existente.
- Drag-and-drop upload/download.
- Markdown editor en browser.

### 9.5.5 Collaborative annotations (CRDT)

- Anotar PDF/MD/Imágenes colaborativamente.
- Yjs o Automerge (custom Kotlin port).
- E2EE entre collaborators (X25519 + AES-GCM).
- Sin servidor central: relay opcional sobre WebRTC.

---

## Phase 9.X — Quick wins (1-3 días cada uno, ongoing)

Estos se meten conforme se vean huecos entre los grandes:

- [ ] Home screen widgets (recents 4x2 / trash / favorites / quick-share)
- [ ] Per-folder color + emoji icon
- [ ] Smart rename con EXIF (fotos: fecha, GPS, modelo cámara)
- [ ] Perceptual hash (pHash) para casi-duplicados
- [ ] File comparison tool (hash / diff / side-by-side)
- [ ] Per-folder autoclean rules ("borrar .tmp >7d automáticamente")
- [ ] Recent files widget
- [ ] Onboarding tooltip system (highlight al primer acceso)
- [ ] Bulk rename UI con templates custom
- [ ] Operations log (audit trail de qué se hizo y cuándo)
- [ ] Shortcuts: long-press menu en home screen para "Nueva nota", "Nuevo folder en Downloads"
- [ ] "Created by me" filter (usa EXIF + atomic file owner tracking)
- [ ] Compute checksums in parallel (use Dispatchers.IO + chunked hashing)
- [ ] File fingerprint visual (color-coded por hash bucket)
- [ ] Bulk operations queue UI con drag-to-reorder
- [ ] File version awareness en viewers (multi-version tabs)
- [ ] Encrypted share (recipient needs vault key share)
- [ ] Background indexer pause-when-low-battery
- [ ] Vault dentro de vault (nested vaults)
- [ ] Annotation layer sobre document viewer (highlights persistentes)

---

## Orden de ejecución recomendado

```
Semana 1-2:   Phase 9.1.1 Network + 9.1.3 Archives (brecha #1)
Semana 3-4:   Phase 9.1.2 Cloud providers (Drive primero)
Semana 5-6:   Phase 9.1.4 PDF editor + 9.1.5 Image ops + 9.1.6 Material You
Semana 7-10:  Phase 9.2.1 Semantic search real (differentiation #1)
Semana 11-13: Phase 9.2.2-3 Auto-tagging evolution + smart folders reactivos
Semana 14-16: Phase 9.3.1 Time-travel + 9.3.2 LAN sync
Semana 17-19: Phase 9.4.1 LLM agent + 9.4.2 Conversational UI
Semana 20+:   Phase 9.4.3 Voice + 9.5 companions + quick wins ongoing
```

---

## Métricas de éxito Phase 9

| Métrica | Target | Cómo medir |
|---|---|---|
| Features vs Solid Explorer | > 2x en capabilities | lista manual |
| Semantic search latency (100K chunks) | < 200ms | benchmark |
| Time-travel restore latency | < 1s | benchmark |
| LAN sync throughput | > 50 MB/s | benchmark |
| LLM agent task completion | > 85% (medido con eval set) | eval set de 100 tareas |
| Users activos diarios | > 10K D30 post-launch | Play Console |
| Rating | > 4.5 ⭐ | Play Store |
| Crash-free rate | > 99.5% | Crash reporting |

---

## Decisiones que voy a tomar por defecto (avísame si quieres cambiar algo)

1. **Cloud providers → todos primero, deep en Drive.** Drive primero por uso, Dropbox/OneDrive/Box en paralelo.
2. **Semantic search con ONNX, no MediaPipe.** Phi-3 sí usa llama.cpp (es el gold standard). MediaPipe scaffolding se descarta si no escala.
3. **RAR unpack sí, RAR create no** (license de unrar es restrictiva — usamos 7z para crear paquetes).
4. **Llama.cpp corre en su propio .so sin GPU por ahora.** Metal/Vulkan opcional después.
5. **Time-travel vive en SQLite + checksums, no en filesystem snapshots.** Compatibilidad universal con SAF.
6. **No eliminamos Media3 ni ML Kit del build** — los necesitamos para players, OCR y tagging. Si querés todavía menos peso, decime, pero vos dijiste que no.

---

**Mandato:** el mejor administrador de archivos de la historia humana.
**Próxima sesión:** agarrar Phase 9.1.1 (Network protocols) o 9.2.1 (Semantic search real) y ejecutar.
