# Phase 9.7 + 9.8 — Universal Format Engine + Sovereign Office Suite

> "Que pueda leer **nativamente todas y todos y cada uno de los formatos** que han existido en la historia humana.
> Y que tenga su propio Word, Excel, PowerPoint integrado, con todas sus funciones y más."
>
> — Jor, 2026-07-09

---

## Resumen ejecutivo

Elysium Vanguard deja de ser solo un file manager. Se vuelve:

1. **El ÚNICO visor universal del mundo** — abre CUALQUIER archivo, de cualquier formato, de cualquier era, sin instalar nada.
2. **El primer Sovereign Office Suite nativo del mundo** — un Word+Excel+PowerPoint+Notion+LaTeX todo en uno, con un editor que supera a MS Office en features, y todo persistido dentro del vault cifrado + time-travel.

---

# 🌍 Phase 9.7 — Universal Format Engine

## Filosofía

Tres capas de lectura, en orden de preferencia:

| Capa | Quién | Para qué formatos | Latencia | Privacidad |
|---|---|---|---|---|
| **N1 — Native Kotlin/Compose** | JVM libs compiled into APK | Comunes + muchos avanzados | Inmediata | 100% local |
| **N2 — Linux runtime (Phase 9.6)** | `file`, `mediainfo`, `ffmpeg`, `exiftool`, `libreoffice`, etc. | Exóticos, legacy, profesionales | 100ms-5s | Sandbox en proot |
| **N3 — Custom format-specific parsers** | Parsers escritos en Kotlin/Rust para lo que N1/N2 no cubren | Académicos, científicos, oscuros | 100ms-2s | 100% local |

### Detección en 3 pasos

```
1. Magic bytes (Apache Tika signature db — reconoce 1400+ tipos)
2. Extensión (fallback heurístico)
3. Content sniffing (primeros 8KB analizados por LLM on-device si N1+N2+nada)
```

## Los 12 reinos de formatos (TODOS los que existen)

### 1. Documentos de texto + ofimática (N1 native + N2 fallback)

#### 1.1 Texto plano
- ASCII, UTF-8, UTF-16, UTF-32
- Latin-1, ISO-8859-{1..16}, Windows-125x
- CJK: GBK/GB18030, Big5, Shift_JIS, EUC-JP, EUC-KR
- Cyrillic: KOI8-R/U, CP1251
- Arabic/Hebrew/Vietnamese/Thai
- **EBCDIC** (mainframe IBM) — usando `iconv` N2

#### 1.2 Markup / lightweight markup
- Markdown (CommonMark + GFM)
- reStructuredText
- AsciiDoc
- LaTeX, BibTeX
- troff/groff, man pages
- HTML, XHTML
- POD (Perl)
- RD (Ruby)
- RDoc
- Doxygen, Javadoc, Sphinx autodoc
- Org-mode
- MediaWiki markup
- BBCode
- Textile
- Wikidot markup
- Creole

#### 1.3 Office documents
- **OOXML (modern MS)**: .docx, .xlsx, .pptx, .dotx, .xltx, .potx, .vsdx, .accdb, .pub
- **OLE2 (legacy MS)**: .doc, .xls, .ppt, .dot, .xlt, .pot
- **OpenDocument**: .odt, .ods, .odp, .odg, .odf, .odc, .odi, .odm
- **Apple iWork**: .pages, .numbers, .key (decrypted si es zip)
- **WordPerfect**: .wpd
- **RTF**
- **WPS Office**: .wps, .wpt, .et
- **Hancom Hangul**: .hwp, .hwpx (.hwp es propietario coreano)
- **JWP, JUST**: .jfw, .jtd (Japanese)
- **AbiWord, KWord, LyX**: .abw, .kwd, .lyx
- **Lotus/Symphony**: .123, .lwp, .mwp, .sam

#### 1.4 E-books
- EPUB 2/3 (full structure rendering)
- Mobipocket .mobi, .azw, .azw3, .kf8
- FB2, FB3 (FictionBook)
- LIT, LRF, LRX
- PDB (Palm), PRC (Palm/Psion)
- DjVu, DjVu bundled
- Comic book archives: .cbr (rar), .cbz (zip), .cbt (tar), .cb7 (7z), .cba (ace — LEGACY)
- Manga: .mng
- Web pub: .opf, .ncx

#### 1.5 Scientific publications
- PDF (todos los flavors, incluyendo PDF/A, PDF/E, PDF/X, PDF/UA, PDF/VT)
- PostScript .ps
- DVI
- TeX DVI
- Encapsulated PS .eps
- ChemDraw .cdx, .cdxml
- Chemical table .sdf, .mol, .rdf
- PDB (Protein Data Bank)
- CIF (Crystallographic Information)
- BIB, RIS, EndNote XML

#### 1.6 Outliner / mind mapping / notes
- OPML
- FreeMind .mm
- MindNode .mindnode
- XMind .xmind
- OmniOutliner .oo3
- WorkFlowy-style nested lists
- Zettelkasten / Roam .json (special parse)
- Tomboy / GNote XML

#### 1.7 Subtitles
- SubRip .srt
- SubViewer .sub
- MicroDVD .sub
- Sub Station Alpha .ssa, .ass
- WebVTT .vtt
- TTML .ttml, .dfxp
- SAMI .smi
- AQTitle .aqt
- RealText .rt
- Universal Subtitle Format .usf

### 2. Imágenes (N1 nativo Android + N2 fallback para exóticas)

#### 2.1 Raster clásicos (Android ya los hace)
JPEG, PNG, GIF animado, BMP, TIFF (todos los subformats), WebP, HEIF, AVIF, JPEG XL, JPEG 2000, PPM/PGM/PBM, TGA, PCX, WBMP

#### 2.2 High-end raster
- PSD (Photoshop) — capas, máscaras, grupos, smart objects
- PSB (large PSB)
- XCF (GIMP nativo)
- KRA (Krita)
- MDP (MediBang)
- TIF/TIFF multi-página
- OpenEXR .exr (HDR, deep)
- DPX (motion picture)
- Cineon .cin
- Radiance HDR .hdr, .pic
- TGA 32-bit (con alpha)
- ILBM .iff, .lbm (Amiga)
- PCX multi-palette

#### 2.3 RAW fotográfico
- ARW (Sony)
- CR2, CR3 (Canon)
- NEF (Nikon)
- ORF (Olympus)
- RW2 (Panasonic/Leica)
- RAF (Fujifilm)
- DNG (Adobe DNG)
- PEF (Pentax)
- SRW (Samsung)
- X3F (Sigma)
- 3FR (Hasselblad)
- MRW (Minolta/Konica)
- IIQ (Phase One)
- DCR (Kodak)
- KDC (Kodak)
- ERF (Epson)
- MEF (Mamiya)
- NRW (Nikon)
- RWZ (Ricoh)
- ORF variants (12+ variantes Olympus)
- HEIC de iPhone (usando libheif)
- Apple ProRAW
- Google Motion Photos (MP + JPEG + metadata)
- Pixel HDR+ (DNG + computation data)

#### 2.4 Vector
- SVG, SVGZ (compressed SVG)
- AI (Adobe Illustrator) — versiones antiguas + PDF-embedded en versiones nuevas
- EPS, EPSF, EPSI
- CDR (CorelDRAW) — versiones 1-24+ (parser custom)
- FIG (XFig)
- SK1 (Sketch/Skencil)
- CGM (Computer Graphics Metafile)
- WMF, EMF (Windows Metafile)
- XAML
- PostScript vector .ps
- PDF vector
- ODG (OpenDocument Graphics)
- VSD, VSDX (Visio) — mismo engine que Office
- DWG, DXF (AutoCAD)
- DrawIO .xml
- Inkscape .svg (con namespaces extendidos)
- Sketch .sketch (Zip-based)
- Figma .fig (Zip-based, JSON)
- Adobe XD .xd
- SketchUp .skp

#### 2.5 Animación
- GIF animado
- APNG
- WebP animado
- AVIF animado
- MNG (Multiple-image Network Graphics)
- JNG (JPEG Network Graphics)
- Lottie JSON (with Bodymovin extension)
- Lottie .lottie (zip)
- Rive .riv

#### 2.6 3D / spatial
- OBJ
- FBX (ASCII + binary)
- glTF, GLB
- USD, USDA, USDC, USDZ
- STL (ASCII + binary)
- PLY
- 3DS (3ds Max)
- COLLADA .dae
- Blender .blend
- Cinema 4D .c4d
- Maya .ma, .mb
- 3ds Max .max
- Modo .lxo
- ZBrush .zpr, .ztl
- Substance .spp
- SketchUp .skp
- STEP / STP (CAD)
- IGES / IGS (CAD)
- IFC (BIM)
- glTF with Draco/Meshopt compression

#### 2.7 Panoramic / VR
- INSV (Insta360)
- INSP
- EQUIRECTANGULAR PNG/JPG (panoramas)
- CUBEMAP layouts
- VR180 (top-bottom, side-by-side)
- VR360

#### 2.8 Médico / científico
- DICOM .dcm (.dicom)
- NIfTI .nii, .nii.gz
- ANALYZE 7.5 .hdr/.img
- MINC .mnc
- MRC .mrc
- SVS (Aperio whole-slide)
- MRXS (MIRAX)
- VMS (Hamamatsu)
- SCN (Leica)
- NDPI (Hamamatsu NDP)
- BIF (Ventana)
- QPTIFF
- CZI (Zeiss)
- LIF (Leica)
- IPLab .ipl
- Imaris .ims

### 3. Audio (N1 ExoPlayer para comunes, N2 ffmpeg para exóticos)

#### 3.1 Lossy
- MP3 (todas las versiones, incluyendo MP3PRO)
- AAC, HE-AAC, xHE-AAC
- M4A, M4B, M4R (iTunes)
- OGG Vorbis
- Opus
- WMA (7, 8, 9, 10 Pro)
- RealAudio .ra, .ram, .rm
- Musepack .mpc
- AC-3, E-AC-3, Dolby Digital
- DTS, DTS-HD
- ATRAC (Sony MiniDisc/ATRAC3/ATRAC3plus/ATRAC Advanced Lossless)
- Dolby TrueHD
- Dolby Atmos (JOC)
- MPEG-4 SLS
- MPEG-H 3D

#### 3.2 Lossless
- FLAC (con cover art, multi-channel, hasta 32-bit/384kHz)
- WAV (todos los bit depths: 8/16/24/32-bit integer, 32/64-bit float)
- AIFF, AIFC
- ALAC (Apple Lossless)
- APE (Monkey's Audio)
- WV (WavPack)
- TTA (True Audio)
- Shorten .shn
- OptimFrog .ofr, .ofs
- LA (Lossless Audio)
- RKA (RKAU)

#### 3.3 Tracker / chiptune
- MOD (Amiga ProTracker)
- XM (FastTracker II)
- IT (Impulse Tracker)
- S3M (Scream Tracker)
- STM
- MED (OctaMED)
- OKT (Amiga Oktalyzer)
- 669 (Composer 669)
- ULT (Ultra Tracker)
- MTM (Multi Tracker)
- FAR (Farandole)
- PSC (PSyclone)
- PTM (Poly Tracker)
- AMS (Extreme's Tracker)
- WOW (Grave Composer)
- AY (ZX Spectrum)
- SID (Commodore 64)
- SAP (Atari XL/XE)
- NSF (NES)
- SPC (SNES)

#### 3.4 MIDI / sheet
- Standard MIDI .mid, .midi
- RIFF MIDI .rmi
- Karaoke .kar
- XMI (XMIDI / Miles Sound System)
- HMI (Human Machine Interfaces)
- MUS (Doom)
- GM/GS/XG
- MLD (MIDI Lyrics Display)
- SMF (Standard MIDI File) 0/1/2
- MusicXML
- MuseScore .mscz, .mscx
- Finale .musx, .ftmx
- Sibelius .sib
- Guitar Pro .gp3, .gp4, .gp5, .gpx, .gp
- PowerTab .ptb
- ABC notation
- Lilypond
- NI Kontakt .nki, .nkm

#### 3.5 DAW projects
- Ableton Live .als
- Logic Pro .logicx, .lso
- Pro Tools .ptx, .ptf, .aaf
- Cubase/Nuendo .cpr
- REAPER .rpp
- FL Studio .flp, .fld
- Studio One .song, .track
- Bitwig Studio .bwproject
- Pro Tools .pts
- Reason .rns, .reason
- GarageBand .band
- LMMS .mmp, .mmpz
- Ardour .ardour
- Mixbus .mxd

#### 3.6 DJ software
- rekordbox .xml, .dat
- Serato .crate, .db
- Traktor .nml
- Virtual DJ .m3u
- Engine (Denon) .den
- Rekordbox 6 .xml
- Mixed In Key .mik
- Lexicon .lex

#### 3.7 Voice / dictation
- AMR (3GPP)
- AMR-WB
- QCP (Qualcomm)
- EVRC
- iLBC
- G.711, G.722, G.723, G.726, G.729

#### 3.8 Bioacoustics / hydrophones
- WAV BARS (bioacoustic recording standard)
- ARABO (bat recordings)
- Audacity custom formats .aup
- Raven Lite .rgn

### 4. Video (N1 ExoPlayer + N2 ffmpeg)

#### 4.1 Containers
- MP4 (todos los flavors)
- MKV (Matroska, WebM)
- AVI
- MOV
- WMV (Windows Media)
- FLV
- OGG (.ogv)
- MPEG-TS (.ts, .m2ts, .mts)
- MPEG-PS (.vob, .mpg)
- 3GP, 3G2
- ASF
- RM, RMVB (RealMedia)
- HEIF video (Apple Live Photos)
- BRAW (Blackmagic RAW)
- R3D (Red)
- MXF (Material Exchange Format)
- GXF
- LXF (Liquid)
- PS (Program Stream)
- DPX

#### 4.2 Codecs
- H.264/AVC (todos los profiles)
- H.265/HEVC (Main, Main 10, Main 12, etc.)
- H.266/VVC
- AV1 (con SVC, todas las capas)
- VP8, VP9
- Theora
- MPEG-1, MPEG-2, MPEG-4 ASP
- Xvid, DivX
- WMV 7/8/9
- ProRes (todos los tipos: 422, 4444, RAW, etc.)
- CineForm
- DNxHR / DNxHD
- AVC-Intra
- XAVC
- REDCODE (R3D)
- ARRIRAW (ARRI)
- CinemaDNG
- Blackmagic RAW
- SHEVC, SH264
- AVS2, AVS3 (Chinese)
- VC-1
- Dirac
- VP6, VP7
- RealVideo
- Indeo

#### 4.3 Streaming formats
- HLS .m3u8
- DASH .mpd
- MSS (Microsoft Smooth Streaming) .ism, .ismc
- CMAF (.cmfv, .cmfa, .cmft)
- MPEG-DASH con segment files
- HTTP Dynamic Streaming (Adobe) .f4m

#### 4.4 Captura / RAW
- DPX secuencias
- CIN secuencias
- EXR secuencias
- BRAW (Blackmagic RAW)
- ARRI RAW .ari
- R3D .r3d
- CDNG (CinemaDNG)
- ARRIRAW

#### 4.5 360/VR
- Equirectangular
- Cubemap
- Top-bottom stereo
- Side-by-side stereo
- FB360, FB360 mono, FB360 stereo
- VR180
- VR360
- Apple MV-HEVC

#### 4.6 Drone / GoPro / action cams
- INSV (Insta360)
- LRV (GoPro low-res)
- GPR (GoPro)
- DJI .mp4 variants
- Autel variants
- Parrot variants

### 5. Compresión / empaquetado (N1 + N2)

#### 5.1 Compresión
- ZIP, ZIPX
- RAR (1.5–7.x incluyendo RAR5)
- 7Z (todas las variantes incluyendo LZMA2)
- TAR
- GZIP, GZ
- BZIP2, BZ
- XZ
- LZMA, LZMA2
- Zstandard .zst
- LZ4
- Brotli .br
- Snappy .sz
- LZO
- LZX
- Commodore LHA
- ARC
- ARJ
- ZOO
- Z (compress/uncompress)
- Pack (.pkz)
- StuffIt .sit, .sitx
- Compact Pro
- BinHex .hqx
- MIME (Base64)
- UUEncode
- XXEncode
- YEnc
- CAB
- ACE (legacy, lectura)
- LHA/LZH

#### 5.2 Disk images / contenedores
- ISO 9660 (con Joliet, Rock Ridge, Apple HFS+, Amiga extensions)
- IMG (raw)
- DMG (UDIF)
- CUE/BIN
- NRG (Nero)
- MDF/MDS
- CDI (DiscJuggler)
- DAA (PowerISO)
- VHD (Microsoft Virtual Hard Disk)
- VHDX
- VMDK (VMware)
- VDI (VirtualBox)
- QCOW2 (QEMU)
- QED
- EBS (Amazon)
- SquashFS .sqsh
- CramFS
- ROM
- CISO / JISO (PSP)
- WBFS (Wii)
- CHD (Compressed Hunks of Data) — MAME/Arena
- CSO (PSP)

### 6. Ejecutables / binarios (N1 + N2)

- **ELF** (Linux): static, dynamic, multi-arch, ARM/ARM64/x86/x86_64/RISC-V, compressed
- **Mach-O** (macOS/iOS): todas las architectures, MH_MAGIC, MH_CIGAM
- **PE/COFF** (Windows): .exe, .dll, .sys, .scr, .cpl, .ocx
- **NE** (16-bit Windows)
- **MZ** (DOS): .com, .exe, .sys
- **a.out**
- **Java Class** (.class)
- **DEX** (Android Dalvik)
- **WASM** (WebAssembly)
- **.NET assemblies** (.dll, .exe en .NET, NativeAOT)
- **XAR** (Apple)
- **Mach-O Universal Binary** (multi-arch)
- **iOS App** (.ipa)
- **APK**, **AAB** (Android)
- **Mac App Bundle** (.app, .pkg)
- **MSI** (Windows Installer)
- **DEB** (Debian package)
- **RPM** (Red Hat package)
- **Pacman packages**
- **pkg.tar.xz** (Arch)
- **AppImage** (Linux portable)
- **Flatpak** (.flatpak, .flatpakref)
- **Snap** (.snap)
- **Nix** (.drv, .nar)
- **firmware**: .bin, .hex, .elf, .dfu, .fw
- **BIOS** .rom, .bin
- **UEFI** .efi

### 7. Filesystem images / discos

- ext2, ext3, ext4
- btrfs
- ZFS stream .zfs, .zv
- XFS dump
- ReiserFS
- F2FS
- NTFS (raw, sparse)
- exFAT
- FAT12/16/32
- HFS, HFS+, HFSX
- APFS
- BCacheFS
- HAMMER, HAMMER2 (DragonFly)
- Amiga OFS, FFS
- Amiga SFS
- Plan9 / Fossil / Venti
- BeOS BFS
- QNX4, QNX6
- Minix fs
- Coherent
- OS-2 HPFS
- AtheOS
- Reiser4
- Lustre

### 8. Fonts / tipografía (N1 nativo Android + N2 fallback)

- TrueType .ttf
- OpenType .otf, .ttc (collection)
- PostScript Type 1 .pfb, .pfm, .afm
- WOFF 1, 1.0, 2
- WOFF 2
- EOT (Embedded OpenType)
- BDF (Bitmap Distribution Format)
- PCF (Portable Compiled Font)
- FNT (Windows)
- FON (Windows Resource)
- bitmap distribution .dfont (Apple)
- NFNT (RISC OS)
- SFD (Spline Font Database)
- UFO (Unified Font Object)
- Glyphs .glyphs
- FontForge .sfd
- NFont (Plan 9)
- iType
- AFM (Adobe Font Metrics)
- INF (Windows)

### 9. Bases de datos / data stores (N1 nativo + N2 fallback)

- SQLite 3 .sqlite, .sqlite3, .db
- MySQL dumps .sql
- PostgreSQL dumps
- dBASE .dbf
- Microsoft Access .mdb, .accdb
- Microsoft Works .wdb
- Paradox .db
- Lotus 1-2-3 .wk1, .wk3, .wk4
- Quattro Pro .qpw, .wb1, .wb2, .wb3
- Borland Paradox .db
- DBF (FoxPro, dBASE)
- Berkeley DB
- GDBM
- NDB (MySQL Cluster)
- LevelDB
- RocksDB
- LMDB
- BoltDB
- Realm .realm
- Datomic
- DataStax Cassanda SSTables
- Apache HFile
- H2 .mv.db
- HSQLDB
- Derby .derby

### 10. Datos columnares / analíticos / big data (N1 + N2)

- Parquet
- ORC
- Avro
- RCFile
- SequenceFile
- Feather (Arrow)
- Arrow IPC
- HDF5
- netCDF 3, 4
- GRIB 1, 2
- BUFR
- NEXRAD
- CMIP
- TSV, CSV, PSV (pipe-separated)
- Apache Arrow .arrow

### 11. Geoespaciales / GIS (N1 + N2)

- Shapefile (shp + shx + dbf + prj)
- GeoJSON
- TopoJSON
- KML, KMZ
- GPX
- GML
- GPKG (GeoPackage)
- MIF/MID (MapInfo)
- TAB (MapInfo)
- GeoTIFF
- ECW (ERMapper Compressed Wavelet)
- MrSID (LizardTech)
- JP2 (JPEG 2000 georeferenced)
- mbtiles
- MBTiles
- TMS (Tile Map Service)
- XYZ tiles
- Vector tiles .pbf, .mvt
- LIDAR .las, .laz, .copc
- DWG/DXF/CAD
- CityGML
- IndoorGML
- IMDF (Indoor Mapping Data Format)

### 12. CAD / ingeniería (N1 custom + N2 fallback)

- AutoCAD .dwg, .dxf, .dwt
- MicroStation .dgn
- CATIA .CATProduct, .CATPart, .CATDrawing, .cgr
- SolidWorks .sldprt, .sldasm, .slddrw
- NX (Siemens) .prt
- Creo (PTC) .prt, .asm
- Inventor .ipt, .iam
- Revit .rvt, .rfa, .rte, .rft
- ArchiCAD .pln, .gsm
- IFC (Industry Foundation Classes) .ifc, .ifcxml
- STEP .stp, .step, .p21
- IGES .igs, .iges
- SAT (ACIS) .sat, .sab
- 3MF (3D Manufacturing Format) .3mf
- AMF (Additive Manufacturing)
- STL (stereolithography, ASCII + binary)
- OBJ
- FBX
- JT (Jupiter Tessellation) .jt
- FBX
- Parasolid .x_t, .x_b
- VDA-FS
- VRML .wrl

### 13. Email / contactos / calendarios (N1 + N2)

- Email: .eml, .msg, .mbox, .maildir, .emlx, .pst, .ost
- Calendars: .ics, .ical, .vcs, .vcf (vCard), .vcf (vCalendar de Nokia)
- Contacts: .vcf (vCard 2.1/3.0/4.0)
- RSS, Atom
- OPML
- AddressBook .abbu (Apple)

### 14. Médico / científico (N1 dcm4che, NCAR, NetCDF-Java, nom-tam, BEAM, etc.)

(Ver sección 2.8 y 10 arriba; específicamente:)
- DICOM completo
- NIfTI .nii, .nii.gz
- Analyze 7.5
- MINC, MINC2
- EDF, EDF+, BDF (biosignals)
- HL7 v2, v3 (healthcare messages)
- FHIR (JSON+XML)
- OpenEHR
- DICOM-SR
- IHE profiles
- VCF 4.2 (genomics)
- BED, BAM, SAM (bioinformatics)
- PDB, mmCIF
- MOL, SDF, RDF (chemistry)
- CCL (Cambridge Crystallography)

### 15. Forensics / security / captura

- pcap, pcapng (network capture)
- dmp (Windows crash)
- core (Unix core)
- minidump
- volatility memory images
- binwalk (embedded files detection)
- foremost output (carved files)
- qcow2 snapshots
- evtx (Windows Event Log)
- Registry hives (SAM, SYSTEM, SOFTWARE, NTUSER)
- MFT, $LogFile, $MFT
- prefetch
- shellbags
- LNK
- JumpList
- hiberfil.sys
- pagefile.sys
- slack space, unallocated clusters
- iOS backup .backup, .sqlite
- Android backup .ab, .tar

### 16. Crypto / blockchain

- Ethereum keystore (JSON)
- Bitcoin wallet.dat
- Monero wallet
- Cardano keys
- IPFS keystore
- SSH keys (PEM, OPENSSH, PPK, RFC4716)
- GPG/PGP keyrings
- PKCS#7, PKCS#12 (.p12, .pfx)
- JKS, JCEKS (Java keystores)
- KMS key material
- Apple Keychain .keychain, .keychain-db
- BitLocker .bek
- FileVault sparsebundle
- VeraCrypt .hc (container)
- EncFS config

### 17. Retro / histórico / vintage

- Commodore 64: .prg, .t64, .d64, .d71, .d81, .g64, .tap, .crt
- ZX Spectrum: .sna, .z80, .tap, .tzx
- Atari 2600: .bin, .a26
- NES: .nes
- SNES: .smc, .sfc
- Sega Master System: .sms
- Sega Genesis: .gen, .md
- Game Boy: .gb, .gbc
- Nintendo DS: .nds
- PlayStation 1: .iso, .bin, .cue
- Amiga: .adf, .adz, .dms, .ipf, .lha, .iff/ilbm
- Apple ][: .dsk, .do, .po, .nib, .woz
- TRS-80: .dsk
- CP/M: .imd
- BBC Micro: .ssd, .dsd
- ZX81: .p, .81
- Acorn Archimedes: .adf
- Intellivision: .int

### 18. Game assets / data

- Quake PAK .pak
- WAD (DOOM)
- BSP (Quake level)
- VPK (Valve)
- GCF, NCF (Valve cache)
- Unity asset bundles
- Unreal UASSET, .UMAP
- Godot .pck, .tres, .tscn
- RPG Maker .rxdata, .rpgproject, .rgssad, .rgss2a, .rgss3a
- Wolfenstein .wl6
- Build engine .grp
- Unreal Tournament .utx

### 19. Streaming / broadcasting

- HLS (.m3u8 + .ts / .m4s)
- DASH (.mpd + .m4s / .webm)
- MSS (.ism, .ismc, .ismv)
- RTMP
- RTSP
- RTP
- SRT (Secure Reliable Transport)
- RIST (Reliable Internet Stream Transport)
- WebRTC (.sdp, .rtp)
- NDI streams

### 20. Config / build / infra

- TOML
- YAML, YAML 1.2
- JSON, JSON5, JSON Lines
- XML (con todos los namespaces)
- INI, CONF, CFG
- .properties, .plist
- systemd unit files
- SysV init scripts
- Dockerfile, Compose
- Vagrantfile
- Terraform .tf, .tfvars
- Ansible playbook
- Chef recipe, .cookbook
- Puppet manifest, .pp
- Helm chart
- Kustomization
- GitHub Actions workflow
- GitLab CI YAML
- CircleCI config
- Drone config
- Buildkite pipeline
- Packer template

### 21. Miscellaneous

- Torrents .torrent
- Magnet links (handle in share UI)
- IPFS CID resolution
- Torrent chunks
- Bitcoin block .blk
- Blockchain .dat
- Ledger entries
- Docker images (compressed filesystem layers)
- OCI images
- Snap packages

---

## Implementación por capa

### N1 — Native (próximas 4-6 semanas)

| Componente | Library | Cubrirá |
|---|---|---|
| `core/format/MagicDetector.kt` | Custom + Apache Tika 2.x | 1400+ tipos detectados |
| `core/format/format-registry.json` | Custom | mapping tipo → renderer |
| `core/format/viewer/TextViewer.kt` | Custom + juniversal-chardet | All encodings (incl. EBCDIC) |
| `core/format/viewer/PdfViewer.kt` | PdfRenderer (AOSP) | PDF todos |
| `core/format/viewer/EpublibViewer.kt` | epublib-core | EPUB |
| `core/format/viewer/DocxViewer.kt` | Apache POI | docx, xlsx, pptx, vsdx, etc. |
| `core/format/viewer/OdtViewer.kt` | ODF Toolkit | ods, odt, odp |
| `core/format/viewer/RtfViewer.kt` | Apache Tika RTF parser | rtf |
| `core/format/viewer/MarkdownViewer.kt` | (ya existe) | md + variants |
| `core/format/viewer/ImageViewer.kt` | (ya existe) + ImageDecoder | jpg, png, gif, webp, heic, avif |
| `core/format/viewer/PsdViewer.kt` | Custom o psd-lib-android | psd capas |
| `core/format/viewer/XcfViewer.kt` | Custom | xcf GIMP |
| `core/format/viewer/RawViewer.kt` | libraw-jni | CR2/CR3/NEF/ARW/RAF/DNG |
| `core/format/viewer/SubtitleViewer.kt` | Custom | srt, vtt, ass |
| `core/format/viewer/MidiViewer.kt` | javax.sound.midi + custom tracker | mid, mod, xm, it, s3m |
| `core/format/viewer/FontViewer.kt` | FreeType + custom | ttf, otf, woff, etc. |
| `core/format/viewer/FontPreviewScreen.kt` | Custom | Preview con texto custom |
| `core/format/viewer/BigImageViewer.kt` | Custom tiled loading | gigapixel TIFF, mosaicos |
| `core/format/viewer/GltfViewer.kt` | filament-android o scene-view | gltf, glb, obj |
| `core/format/viewer/StlViewer.kt` | Custom | stl |
| `core/format/viewer/SvgViewer.kt` | AndroidSVG | svg, svgz |
| `core/format/viewer/MeshViewer.kt` | assimp-jni | fbx, dae, 3ds, blend |
| `core/format/viewer/UsdzViewer.kt` | Quick Look for Android | usdz, reality |
| `core/format/viewer/UsdzPreview.kt` | Custom | USD |
| `core/format/viewer/AudioPlayer.kt` | (ya existe media3-exoplayer) | mp3, m4a, ogg, flac, opus, wma |
| `core/format/viewer/VideoPlayer.kt` | (ya existe media3) | mp4, mkv, webm, avi |
| `core/format/viewer/VideoPlayerAdvanced.kt` | Custom con ffmpeg-kit-full | Todos los demas incluyendo r3d, braw, dnxhr, etc. |
| `core/format/viewer/CompressionBrowser.kt` | Apache Commons Compress + junrar | zip, rar, 7z, tar, gz, bz2, xz, etc. |
| `core/format/viewer/ApkViewer.kt` | Custom (dex2jar-like) | apk: classes, resources, manifest |
| `core/format/viewer/IpaViewer.kt` | Custom (Zip + Info.plist) | ipa |
| `core/format/viewer/DebViewer.kt` | Custom | deb contents |
| `core/format/viewer/DmgViewer.kt` | Custom (UDIF parser) | dmg contents |
| `core/format/viewer/ElfViewer.kt` | Custom | ELF with sections, symbols |
| `core/format/viewer/MachoViewer.kt` | Custom | Mach-O |
| `core/format/viewer/PeViewer.kt` | Custom | PE/COFF |
| `core/format/viewer/DexViewer.kt` | Custom (smali-like) | classes, methods |
| `core/format/viewer/WasmViewer.kt` | Custom | wasm disassembled |
| `core/format/viewer/DicomViewer.kt` | dcm4che | dcm + RTSTRUCT |
| `core/format/viewer/NiftiViewer.kt` | Custom / niftij | nii medical |
| `core/format/viewer/EmailViewer.kt` | Apache James Mime4j | eml, msg, mbox |
| `core/format/viewer/MailPstViewer.kt` | libpff-pst | pst ost |
| `core/format/viewer/IcsViewer.kt` | Custom | ics/vcal |
| `core/format/viewer/VcfViewer.kt` | Custom | vcard |
| `core/format/viewer/TorrentViewer.kt` | Custom (BEP-9) | .torrent content |
| `core/format/viewer/CadViewer.kt` | Open CASCADE Android (OCCT) | dxf, step, iges |
| `core/format/viewer/GpkgViewer.kt` | Custom | GeoPackage |
| `core/format/viewer/ShapefileViewer.kt` | GeoTools Android | shp |
| `core/format/viewer/GeoTiffViewer.kt` | Custom georeferenced render | tif with geo metadata |
| `core/format/viewer/Hdf5Viewer.kt` | HDF5 Java | h5 |
| `core/format/viewer/NetcdfViewer.kt` | NetCDF Java | nc |
| `core/format/viewer/FitsViewer.kt` | nom-tam-fits | fits |
| `core/format/viewer/MidiAdvancedViewer.kt` | Custom | XYZ sheet rendering |
| `core/format/viewer/ConsolesViewer.kt` | Custom (libretro-android) | .nes, .sms, .gb, .gba, .nds, .sfc, .md, .gba, .a26, .int, .col, .cof |
| `core/format/viewer/EmulatorViewer.kt` | libretro | snes64, psx, segacd, etc. |
| `core/format/viewer/AdfViewer.kt` | Custom (Amiga FF) | adf amiga |
| `core/format/viewer/DiskImageViewer.kt` | Custom / libdisk | d64, dsk, woZ |

### N2 — Linux runtime fallback (Phase 9.6) — hace el resto

Comandos disponibles vía filesystem bridge:
- `file <f>` → magic + extendido
- `mediainfo -f <f>` → metadata completa
- `ffprobe -show_format <f>` → video/audio info
- `exiftool <f>` → ALL metadata
- `strings -e l -e b <f>` → strings con encoding detection
- `tesseract <f> stdout -l <lang>` → OCR
- `pandoc <f> -t markdown` → universal converter
- `libreoffice --headless --convert-to <format> <f>` → office render
- `inkscape <f> --export-type=png` → SVG render
- `gimp -b '(...)' <f>` → image render
- `blender -b -P render.py <f>` → 3D render
- `ffmpeg -i <f> frame.png` → video frame
- `unrar / 7z / unzip / untar` → archive extract
- `binwalk -e <f>` → embedded extraction
- `foremost -i <f>` → file carving
- `qemu-img info <f>` → disk image info
- `xorriso -indev <f>` → ISO inspection
- `lexbor / readability` → HTML rendering
- `pandoc -t latex <f>` → LaTeX
- `pandoc -t docx <f>` → DOCX
- `pandoc -t beamer <f>` → slides
- `ghostscript <f>` → PS/PDF
- `djvulibre` → DjVu render
- `cdrtools` → CD image
- `mame -rompath <f>` → ROMs emulation
- `wine <f>` → Windows .exe (stretch)

### N3 — Custom format-specific parsers (lo que N1/N2 no cubren)

- Apple iWork (Pages/Numbers/Keynote): format propietario, parcialmente reverse-engineered — usaremos N2 + custom parser
- CDR (CorelDRAW): parser custom
- HWP (Hancom): parser custom
- AutoCAD DWG modern: usa ODA File Converter (LGPL) o N2 fallback
- Apple Logic, Pro Tools project files: N2 o custom
- REAPER .rpp files: custom parser
- Avid .aaf: N2 fallback

---

# 📝 Phase 9.8 — Sovereign Office Suite

## La promesa

Elysium Office es **Word + Excel + PowerPoint + Notion + LaTeX en uno, on-device, AI-native**.

Persiste todo en **.elysium** format (open-source, ZIP + JSON + binary cells) o exporta directo a:
- .docx, .xlsx, .pptx (full roundtrip con MS Office 365 / Apple iWork / LibreOffice)
- .odt, .ods, .odp (OpenDocument)
- .pdf (export de alta calidad)
- .md (Markdown con todas las extensiones)
- .tex (LaTeX editable)
- .tex (rendered to PDF)
- HTML (self-contained SPA)

## Componentes principales

### 9.8.1 Elysium Word (procesador)

**Trinity UX:**
- Vista de edición (escribir)
- Vista de lectura (limpia)
- Vista de documentación (outline + sidebar con footnotes, glossary)
- Vista mindmap (auto-generado por LLM)
- Vista graph (knowledge graph conectando docs relacionados)

**Features que MEJORAN a Office:**

1. **AI co-writing en vivo** (Phi-3-mini on-device)
   - "Continúa en mi estilo"
   - "Reescribe para claridad lector-audiencia X"
   - "Resume en 3 párrafos"
   - "Expande con ejemplos"
   - "Sugiere la siguiente sección"
   - Toda sugerencia en un **sidebar aprobado por user** (Ctrl+Enter para aceptar)

2. **Voice dictation** (Whisper-tiny on-device)
   - Doble tap en microphone, habla, ve aparecer el texto.

3. **Real-time translation** (NLLB-200 on-device)
   - "Traducir a inglés" → reescribe el doc EN INGLÉS en la misma ventana, no popup.
   - "Escribir en español, mostrar en inglés" — simultáneo.

4. **Inline semantic search desde la vault**
   - `/search cielo azul` → inserta párrafo citado de otro doc.

5. **Smart citations** (con APIs arXiv, Crossref, Semantic Scholar)
   - `/cite 2501.01234` → auto-fetch y formato.
   - BibTeX, RIS, EndNote style export.

6. **Version history por párrafo** (CRDT-like)
   - Mueve un slider para ver cada versión de un párrafo.
   - Re-acepta o rechaza.

7. **Multi-language simultáneo**
   - Texto en español, vista previa en inglés en vivo.

8. **Tag-based knowledge graph**
   - Cada doc es un nodo. Tags generan links.
   - Backlinks auto-detectados por LLM (lee todo, encuentra referencias).

9. **Pluggable exporters**
   - pandoc-powered: markdown ↔ docx ↔ odt ↔ pdf ↔ latex ↔ epub ↔ html ↔ docusaurus ↔ mdx ↔ jupyter.

10. **OCR-to-doc** (tesseract via 9.6)
    - Foto de una página → texto editable con layout.
    - Multi-language detectado.

11. **Bibliography manager**
    - Auto-fetch desde DOI/arXiv.
    - Cite as you write.
    - Format: APA 7, IEEE, ACM, Chicago, MLA, Harvard, etc.

12. **Annotations persistentes** (highlights, sticky notes)
    - Van al vault tag-system.
    - Cross-doc: ver todas las anotaciones en /annotations/highlights.

13. **Zettelkasten workflow built-in**
    - Atomic notes, transclusion, backlinks automáticos.

14. **Smart layout** (Compose-based)
    - Layouts fluidos con paginación read-time (no print).
    - Columnas, sidebars, callouts, expandable blocks.

15. **Reading mode / Focus mode**
    - Elimina distraction, foco en lo importante.
    - Auto-fade de UI; tipo iA Writer pero con todo esto.

### 9.8.2 Elysium Sheet (Excel-equivalent)

**Trinity UX:**
- Vista de celdas (Excel)
- Vista de cards (Notion)
- Vista schema (database)
- Vista canvas (free-form)
- Vista dashboard (cards + charts + tablas)

**Features que MEJORAN a Excel:**

1. **Live data feeds** (sensor, API, RSS, file watcher)
   - Celda con fórmula `=LIVE(https://api.example.com/data, "$.field")`
   - Poll interval.
   - Visual diff cuando cambia.

2. **AI data analyst** (LLM on-device)
   - "Cuál es la correlación entre A y B?"
   - "Detecta outliers"
   - "Predice Q4"
   - "Limpia missing values"
   - "Explica este patrón"

3. **Python-powered UDFs** (via 9.6)
   - Celda con código Python ejecutándose en el distro Linux.
   - Pandas, NumPy, SciPy disponibles.
   - Retorna dataframe que llena celdas.

4. **Symbolic math**
   - Sympy-powered.
   - Ecuaciones simbólicas: `=integral(x^2, x)` → resultado algebraico.

5. **What-if Monte Carlo**
   - Define dist de probabilidad para inputs.
   - 10K simulaciones automáticamente.
   - Distribución del outcome como histograma.

6. **Time-series forecasting built-in**
   - Prophet, ARIMA, ETS, Naive.

7. **Cross-sheet semantic search**
   - "Cuál es la celda donde dice 'total proyectado 2024'?"

8. **Pivot tables con AI suggestions**
   - Drag-and-drop, AI sugiere dimensiones relevantes.

9. **Charts que responden a voice**
   - "Haz este gráfico más pequeño" / "Cambia el color".

10. **Collaborative real-time editing**
    - Cell-level CRDT (Yjs-like).
    - "Edit conflict resolver" estilo Google Docs.

11. **Database-grade queries**
    - SQL nativo: `SELECT a, b FROM Sheet WHERE c > 100`.
    - Multi-sheet joins.

12. **Auto-clean & normalize**
    - Detecta missing, outliers, type mismatches.
    - Suggestion mode (not auto-fix).

13. **Annotation layer**
    - Anchors a cell ranges.
    - Notes persistentes.

14. **Plot types**: scatter, line, bar, area, heatmap, candlestick, OHLC, contour, surface, geographic, treemap, sunburst, sankey, gauge, bullet, parallel coordinates.

15. **Custom functions** (define your own)
    - Typados, con signatures, docstrings.
    - Compartibles como plugins.

### 9.8.3 Elysium Deck (PowerPoint-equivalent)

**Trinity UX:**
- Vista de slides (lineal)
- Vista de outline (estructura)
- Vista timeline (cronología)
- Vista board (mural de cards)
- Vista present (fullscreen)

**Features que MEJORAN a PowerPoint:**

1. **AI-deck generation**
   - "Crea una presentación sobre cambio climático para audiencia de negocios"
   - LLM genera outline → slides → contenido → notas.
   - User aprueba slide-por-slide.

2. **Voice narration** (TTS on-device)
   - Cada slide con audio narrado.
   - Multi-language: narra en español, contenido en inglés.

3. **Speaker mode + teleprompter**
   - Stage mode con notas, timer, next slide preview.

4. **Live polling** (audience voting)
   - QR code en slide → audiencia vota → chart se actualiza live.
   - E2EE entre presenter y audience.

5. **AR mode (Phase 9.5 integration)**
   - Proyecta slides en cualquier superficie.
   - 3D mode en Vision Pro / Quest 3.

6. **Auto-animation con LLM**
   - LLM propone transiciones y animations por slide.
   - User preview, acepta/rechaza.

7. **Theme generator from image**
   - Sube foto → extrae paleta + fuentes.

8. **Audience analytics** (si se comparte link)
   - Quién está viendo, cuánto, qué slides.

9. **Branching narratives**
   - Slides que cambian según decisiones del lector.
   - Elecciones pre-definidas o libres.

10. **3D slide transitions**
    - Cubic flip, galaxy spiral, hemisphere.

11. **Code-as-slide**
    - CodeSandbox embedded live.
    - React a cambios en tiempo real.

12. **Chart-in-slide con live data** (reusa Sheet)
    - Charts live atados a un Sheet.

13. **Auto-captioning** (transcription while speaking)

14. **Recording mode**
    - Presenta + graba → .webm con notas sincronizadas.

15. **AI feedback coach**
    - Graba tu presentación → LLM analiza pace, filler words, claridad → sugiere mejoras.

### 9.8.4 Format Engine (save & export)

| Formato | Write support | Mejor que Office porque |
|---|---|---|
| .docx | ✅ full | AI co-writer, voice, version por párrafo |
| .xlsx | ✅ full | Python UDFs, AI analyst, live data |
| .pptx | ✅ full | AI generator, AR, branching |
| .odt, .ods, .odp | ✅ full | open source compatible |
| .pdf | ✅ export alta calidad | parsing round-trip |
| .epub | ✅ export | e-book generation |
| .md | ✅ round-trip | con extensions Elysium |
| .tex | ✅ round-trip | con AI-enhanced equations |
| .docusaurus/.mdx | ✅ export | docs as code |
| Jupyter notebook | ✅ export | scientific reproducible |
| HTML standalone | ✅ export | self-contained SPA |

### 9.8.5 Plugin system para nuevos "office apps"

Elysium Office es extensible:
- **Sketches** (drawing canvas)
- **Whiteboards** (colaborative mural)
- **Forms** (datasheets, encuestas)
- **Databases** (Notion-style con tipos, relaciones, vistas)
- **Wiki** (link-based knowledge base)
- **Kanban** (cards & boards)
- **Calendar** (eventos)
- **Task list / project management**
- **CRM lite** (contacts + deals)
- **Inventory** (stock management)
- **Music notation** (Score + sheet music)
- **Flowchart** (diagrams)
- **Mind-map** (visualización)

Cada uno es un plugin que instala funcionalidad. Plugin API = Archivos `.elysium-plugin` (zip con JSON manifest + assets).

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│               Elysium Universal Viewer                │
│                                                       │
│   ┌───────────┐    ┌────────────────┐                  │
│   │ Format    │───▶│ Format Selector │                 │
│   │ Detector  │    │ (registry)      │                 │
│   └───────────┘    └────────┬───────┘                  │
│                             │                          │
│       ┌─────────────────────┼──────────────────┐       │
│       │                     │                  │       │
│  ┌────▼──────────┐  ┌──────▼───────┐  ┌────────▼─────┐│
│  │ Native Viewer │  │ Linux Runtime│  │ Custom Parser ││
│  │ (N1)          │  │ Viewer (N2)  │  │ (N3)         ││
│  └───────────────┘  └──────────────┘  └──────────────┘│
│                                                       │
│  ┌─────────────────────────────────────────────────┐  │
│  │ Universal Viewer UI (Composable Compose)        │  │
│  │ • Multi-tab                                     │  │
│  │ • Side-by-side                                  │  │
│  │ • Diff viewer                                   │  │
│  │ • Format-aware toolbar                          │  │
│  │ • AI assistance panel                           │  │
│  └─────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│               Elysium Office Suite                    │
│                                                       │
│   ┌───────────┐  ┌───────────┐  ┌───────────┐         │
│   │ Elysium   │  │ Elysium   │  │ Elysium   │         │
│   │ Word      │  │ Sheet     │  │ Deck      │         │
│   └─────┬─────┘  └─────┬─────┘  └─────┬─────┘         │
│         └──────────────┴──────────────┘               │
│                       │                               │
│         ┌─────────────┴─────────────┐                 │
│         │                            │                │
│   ┌─────▼─────────┐    ┌─────────────▼──────┐         │
│   │ Block Engine  │    │ Plugin Engine       │        │
│   │ (Notion-style)│    │ (extensibility)     │        │
│   └───────────────┘    └────────────────────┘         │
│                                                       │
│   ┌─────────────────────────────────────────────────┐ │
│   │ Core: persistence (.elysium), CRDT, AI, vault  │ │
│   └─────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

---

## Plan de implementación (24 meses)

| Quarter | Foco |
|---|---|
| Q1 | Phase 9.6 — Sovereign Linux Runtime |
| Q2 | Phase 9.7.1-7.4 — Format detection + common N1 viewers |
| Q3 | Phase 9.7.5-7.8 — Exotic N1 viewers (audio/video/3D/CAD) + N2 fallback wired |
| Q4 | Phase 9.8.1 — Elysium Word (AI co-write, voice, version, citation) |
| Q5 | Phase 9.8.2 — Elysium Sheet (AI analyst, Python UDF, live data) |
| Q6 | Phase 9.8.3 — Elysium Deck (AI generator, AR, branching) |
| Q7 | Phase 9.8.4 — Plugin system + first 10 plugins |
| Q8 | Polish, docs, test coverage, store preview |

**Total:** ~24 meses para el MVP completo del "Universal Viewer + Sovereign Office Suite".
**Pero:** cada quarter ya hay un milestone visible y publicable.

---

## Decisiones por defecto que tomo

1. **On-device AI** para todo (Phi-3 + Whisper + NLLB). Sin cloud, sin telemetría.
2. **Open format** `.elysium` (JSON + binary) para nuestros docs nativos. Adicionalmente, full roundtrip con OOXML/ODF/iWork.
3. **Plugins open source, no DRM.** Cualquiera puede hacer uno.
4. **CRDT-based** colaboración desde día 1.
5. **Linux runtime = N2 fallback** automático cuando N1 no existe.
6. **No subscription.** Todo el bundle, una licencia perpetua.
7. **.elysium = ZIP estándar + JSON manfiest + binary blob.** Abrible con cualquier herramienta.

---

## Comparativa con la competencia

| Capability | MS Office 365 | Google Workspace | LibreOffice | Notion + plugins | **Elysium Office** |
|---|:-:|:-:|:-:|:-:|:-:|
| On-device AI agent | ❌ (cloud Copilot) | ❌ (cloud) | ❌ | ❌ | ✅ Phi-3 |
| Voice dictation offline | ❌ | ❌ | ❌ | ❌ | ✅ |
| Round-trip all formats | parcial | ❌ docx only | ✅ | ❌ | ✅ |
| File manager integration | ❌ | ❌ | ❌ | ❌ | ✅ |
| Vault encryption | ❌ | ❌ | parcial | ❌ | ✅ post-quantum |
| Time-travel versions | ❌ (VersionHist limitado) | ❌ | ❌ | ✅ per-block | ✅ slider global |
| Semantic search across docs | ❌ | parcial | ❌ | parcial | ✅ on-device |
| Real-time collaboration | ✅ | ✅ | ❌ | ✅ | ✅ E2EE P2P |
| Offline-first | ❌ (cloud-tied) | ❌ | ✅ | ❌ | ✅ |
| Cross-platform | ✅ | ✅ web | ✅ | ✅ web | ✅ native + web |
| Plugins extendable | ✅ (paid) | ❌ | ✅ | ✅ | ✅ open |
| Vault sees docs as files | ❌ | ❌ | ❌ | ❌ | ✅ |

**Nadie tiene esta combinación.**

---

## Cómo arrancamos

Ahora mismo. Phase 9.6.1 ya — vendor Termux + Compose wrapper. Es la base que destraba todo lo demás.

Después Phase 9.7.1-9.7.4 (formato detection + natives comunes) en paralelo con Phase 9.6.

Después Phase 9.8 según el plan trimestral.

Tú dime por dónde quieres ir, Jor. ¿9.6.1 (terminal + Linux) o 9.7.1 (format detection) o 9.8 (office)? Yo voy con 9.6.1 primero.
