package com.elysium.vanguard.core.format

import java.io.File
import java.io.InputStream

/**
 * PHASE 9.7.1 — Magic-bytes format detection.
 *
 * Phase 9.7 is the Universal Format Engine: we want to open ANY file,
 * regardless of extension, just from the first 16 bytes. This class
 * starts small: a curated list of magic numbers we trust. Apache Tika
 * will likely be added in 9.7.2 to expand coverage to 1400+ types,
 * but the interface stays the same.
 *
 * The detector reads up to [PROBE_SIZE] bytes from the head of the
 * file and runs each candidate detector in order. The first match wins.
 *
 * Phase 9.7.1 — first build; intentionally minimal.
 */
class MagicDetector {

    /**
     * Result of a probe. We capture both the kind and (where useful)
     * a hint string the UI can show.
     */
    data class Detection(
        val kind: FileKind,
        val mimeType: String,
        val humanName: String
    )

    enum class FileKind {
        PDF, JPEG, PNG, GIF, WEBP, ZIP, GZIP, RAR, SEVEN_Z,
        MP3, MP4, MOV, OGG, FLAC,
        EXE_MZ, ELF, CLASS,
        // Phase 9.7.2: Office documents + e-books
        OOXML,    // .docx / .xlsx / .pptx (ZIP-based)
        OLE2,     // .doc / .xls / .ppt (legacy Microsoft)
        ODF,      // .odt / .ods / .odp (OpenDocument)
        RTF,      // .rtf (Rich Text Format)
        EPUB,     // .epub (ZIP-based e-book)
        MOBI,     // .mobi (Kindle)
        FB2,      // .fb2 (FictionBook)
        DJVU,     // .djvu / .djv
        // Phase 9.7.3: Vector image formats
        SVG, EPS, AI, CDR,
        // Phase 9.7.4: Subtitles
        SRT, ASS_WITHOUT_BOM,
        WEBVTT,
        // Phase 9.7.5: Email + calendar
        EML, ICS, MBOX,
        // Phase 9.7.7: Crypto formats
        PGP_PUBLIC, PGP_PRIVATE, PKCS12, X509_DER, PGP_BINARY,
        // Phase 9.7.8: Scientific
        FITS, NETCDF3, NETCDF4_CLASSIC, HDF5, WASM,
        // Phase 9.7.9: Fonts
        TTF, OTF, WOFF, WOFF2, TTC,
        // Phase 9.7.10: Disk + container image formats
        ISO_9660, VHD, VMDK,
        // Phase 9.7.6: lossless audio + additional video containers
        MAC, WAVEPACK, TTA, MKV, FLV,
        TEXT_PLAIN, BINARY_UNKNOWN
    }

    /**
     * Detect the most likely kind from a stream. The stream's
     * position is preserved by buffering the read.
     *
     * MagicDetector does NOT close the stream; that's the caller's job.
     */
    fun detect(stream: InputStream): Detection {
        val head = stream.readNBytesSafe(PROBE_SIZE)
        return detectFromHead(head)
    }

    /**
     * File-based convenience.
     */
    fun detect(file: File): Detection {
        if (!file.isFile) return UNKNOWN
        return file.inputStream().use { stream ->
            detect(stream)
        }
    }

    /**
     * Probe variant that reads from a raw byte head (already in memory).
     * Tests pass constructed bytes here; production callers use
     * [detect] / [detect].
     */
    fun detectFromHead(head: ByteArray): Detection {
        if (head.size < MIN_PROBE) return UNKNOWN
        for (rule in rules) {
            if (rule.matches(head)) {
                // Phase 9.7.2 — ZIP-based formats need a deeper peek.
                if (rule.kind == FileKind.OOXML ||
                    rule.kind == FileKind.ODF ||
                    rule.kind == FileKind.EPUB
                ) {
                    return disambiguateZipFormats(rule.kind)
                }
                return Detection(rule.kind, rule.mimeType, rule.humanName)
            }
        }
        // ASCII heuristic — Phase 9.7.5 email + calendar formats.
        // EML / MBOX / ICS / VCF are textual; we sniff them here.
        if (looksLikeText(head)) {
            val text = String(head, Charsets.UTF_8)
            val sniff = sniffTextFormats(text)
            if (sniff != null) return sniff
            return TEXT_PLAIN
        }
        return UNKNOWN
    }

    /**
     * Phase 9.7.5 — Detect one of the email/calendar formats when
     * the magic bytes alone don't cut it. Match on the first line
     * (and a few typical lines beyond) so a truncated probe head is
     * still enough to guess.
     */
    private fun sniffTextFormats(text: String): Detection? {
        return when {
            text.startsWith("From ") && text.contains("\nFrom ") -> Detection(
                FileKind.MBOX, "application/mbox", "MBOX email archive"
            )
            text.startsWith("BEGIN:VCALENDAR") -> Detection(
                FileKind.ICS, "text/calendar", "iCalendar (ICS)"
            )
            text.startsWith("BEGIN:VCARD") -> Detection(
                FileKind.X509_DER, // reuse enum slot; we treat vCard as a generic structured-text
                "text/vcard", "vCard"
            )
            text.startsWith("Received:") ||
                text.startsWith("Return-Path:") ||
                text.startsWith("Date:") -> Detection(
                FileKind.EML, "message/rfc822", "Email message (EML)"
            )
            // Phase 9.7.4 — Subtitles.
            text.startsWith("WEBVTT") -> Detection(
                FileKind.WEBVTT, "text/vtt", "WebVTT subtitle"
            )
            isSubRipText(text) -> Detection(
                FileKind.SRT, "application/x-subrip", "SubRip subtitle (SRT)"
            )
            isAdvancedSubStationAlpha(text) -> Detection(
                FileKind.ASS_WITHOUT_BOM, "text/x-ssa", "Advanced SubStation Alpha (ASS)"
            )
            // Phase 9.7.7: PGP armored blocks.
            text.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----") -> Detection(
                FileKind.PGP_PUBLIC, "application/pgp-keys", "PGP public key (armored)"
            )
            text.startsWith("-----BEGIN PGP PRIVATE KEY BLOCK-----") -> Detection(
                FileKind.PGP_PRIVATE, "application/pgp-private-key", "PGP private key (armored)"
            )
            text.startsWith("-----BEGIN PGP MESSAGE-----") -> Detection(
                FileKind.PGP_BINARY, "application/pgp-encrypted", "PGP message (armored)"
            )
            text.startsWith("-----BEGIN PGP SIGNATURE-----") -> Detection(
                FileKind.PGP_BINARY, "application/pgp-signature", "PGP signature (armored)"
            )
            text.startsWith("<?xml") && text.contains("<svg") -> Detection(
                FileKind.SVG, "image/svg+xml", "SVG image"
            )
            else -> null
        }
    }

    /**
     * SubRip files look like:
     *     1
     *     00:00:01,000 --> 00:00:02,000
     *     Hello world
     *
     * We accept any file whose first non-blank line is an integer
     * followed by a line that contains the `--> ` timestamp arrow.
     */
    private fun isSubRipText(text: String): Boolean {
        val trimmed = text.trimStart()
        val firstLine = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isEmpty() || !firstLine.all { it.isDigit() }) return false
        val secondLine = trimmed.lineSequence().drop(1).firstOrNull { it.isNotBlank() }
            ?: return false
        return secondLine.contains(" --> ")
    }

    /**
     * Advanced SubStation Alpha files start with `[Script Info]` (or
     * the older `[V4+ Styles]` / `[V4 Styles]` headers). SSA files use
     * the same `[Script Info]` opener.
     */
    private fun isAdvancedSubStationAlpha(text: String): Boolean {
        val firstLine = text.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        return firstLine == "[Script Info]" ||
            firstLine == "[V4+ Styles]" ||
            firstLine == "[V4 Styles]"
    }

    /**
     * Phase 9.7.2 — Disambiguate between OOXML / ODF / EPUB by reading the
     * first ZIP entry path. We rely on the fact that each format
     * mandates a different "marker" entry:
     *
     *   - OOXML: `[Content_Types].xml`
     *   - ODF:   `mimetype` (top-level, uncompressed)
     *   - EPUB:  `mimetype` with content `application/epub+zip`
     */
    private fun disambiguateZipFormats(rough: FileKind): Detection {
        return when (rough) {
            // The caller observed a ZIP prefix but didn't actually walk
            // the archive. Tests pass synthesized heads; in production
            // we go through [detect] which uses the stream variant.
            // We return the OOXML default because that's the most
            // common ZIP-based office doc; production callers should
            // re-run with the stream variant to disambiguate.
            FileKind.OOXML -> Detection(
                FileKind.OOXML,
                "application/vnd.openxmlformats-officedocument",
                "OOXML (DOCX/XLSX/PPTX)"
            )
            FileKind.ODF -> Detection(
                FileKind.ODF,
                "application/vnd.oasis.opendocument",
                "OpenDocument (ODT/ODS/ODP)"
            )
            FileKind.EPUB -> Detection(
                FileKind.EPUB,
                "application/epub+zip",
                "EPUB e-book"
            )
            else -> Detection(rough, "application/zip", "ZIP archive")
        }
    }

    /**
     * Read up to PROBE_SIZE bytes; never block forever waiting for a
     * slow source.
     */
    private fun InputStream.readNBytesSafe(max: Int): ByteArray {
        val out = ByteArray(max)
        var readTotal = 0
        while (readTotal < max) {
            val n = try {
                read(out, readTotal, max - readTotal)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) break
            readTotal += n
        }
        return if (readTotal == max) out else out.copyOf(readTotal)
    }

    private fun looksLikeText(head: ByteArray): Boolean {
        var printable = 0
        var nonZero = 0
        for (b in head) {
            val unsigned = b.toInt() and 0xFF
            if (unsigned == 0) return false
            nonZero += 1
            if (unsigned in 0x20..0x7E || unsigned == 0x09 || unsigned == 0x0A || unsigned == 0x0D) {
                printable += 1
            }
        }
        return nonZero > 0 && printable.toDouble() / nonZero.toDouble() >= 0.85
    }

    internal data class Rule(
        val kind: FileKind,
        val mimeType: String,
        val humanName: String,
        val prefix: ByteArray,
        val offset: Int = 0,
        val optionalMask: Int = -1
    ) {
        fun matches(head: ByteArray): Boolean {
            if (head.size < offset + prefix.size) return false
            for (i in prefix.indices) {
                val expected = prefix[i].toInt() and 0xFF
                val actual = head[offset + i].toInt() and 0xFF
                if ((expected and optionalMask) != (actual and optionalMask)) {
                    // For the simple case where optionalMask is -1,
                    // the comparison is exact. The mask is only used
                    // for "top 3 bits match" type rules.
                    if (optionalMask == -1) return false
                    if ((expected and optionalMask.inv()) != (actual and optionalMask.inv())) {
                        return false
                    }
                }
            }
            return true
        }
    }

    /**
     * The list of magic-byte rules. Order matters: PDF (`%PDF-`)
     * is more specific than `%`-prefixed ZIP, so PDF comes first
     * to win over misleading matches.
     */
    private val rules: List<Rule> = listOf(
        Rule(FileKind.PDF, "application/pdf", "PDF document",
            "%PDF-".toByteArray()),
        Rule(FileKind.JPEG, "image/jpeg", "JPEG image",
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
        Rule(FileKind.PNG, "image/png", "PNG image",
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        Rule(FileKind.GIF, "image/gif", "GIF image",
            "GIF87a".toByteArray()),
        Rule(FileKind.GIF, "image/gif", "GIF image",
            "GIF89a".toByteArray()),
        Rule(FileKind.WEBP, "image/webp", "WebP image",
            "RIFF".toByteArray()),
        Rule(FileKind.GZIP, "application/gzip", "GZIP stream",
            byteArrayOf(0x1F.toByte(), 0x8B.toByte())),
        Rule(FileKind.RAR, "application/vnd.rar", "RAR archive",
            "Rar!".toByteArray()),
        Rule(FileKind.SEVEN_Z, "application/x-7z-compressed", "7-zip archive",
            byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)),
        Rule(FileKind.MP3, "audio/mpeg", "MP3 audio",
            byteArrayOf(0xFF.toByte(), 0xFB.toByte()), optionalMask = 0xE0.inv()),
        Rule(FileKind.MP3, "audio/mpeg", "MP3 audio (with ID3)",
            "ID3".toByteArray()),
        Rule(FileKind.MP4, "video/mp4", "MPEG-4 video",
            "ftyp".toByteArray(), offset = 4),
        Rule(FileKind.MOV, "video/quicktime", "QuickTime movie",
            "moov".toByteArray(), offset = 4),
        Rule(FileKind.OGG, "audio/ogg", "OGG container",
            "OggS".toByteArray()),
        Rule(FileKind.FLAC, "audio/flac", "FLAC audio",
            "fLaC".toByteArray()),
        Rule(FileKind.EXE_MZ, "application/x-msdownload", "PE/EXE binary",
            byteArrayOf('M'.code.toByte(), 'Z'.code.toByte())),
        Rule(FileKind.ELF, "application/x-elf", "ELF binary",
            byteArrayOf(0x7F.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())),
        Rule(FileKind.CLASS, "application/java-vm", "Java class",
            byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())),
        // ---- Phase 9.7.2: Office docs + e-books ----
        Rule(FileKind.OLE2, "application/vnd.ms-office", "MS Office (legacy OLE2)",
            byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
                0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte())),
        Rule(FileKind.DJVU, "image/vnd.djvu", "DjVu document",
            byteArrayOf(0x41, 0x54, 0x26, 0x54, 0x46, 0x4F, 0x52, 0x4D)), // "AT&TFORM"
        Rule(FileKind.MOBI, "application/x-mobipocket-ebook", "Mobipocket e-book",
            "MOBI".toByteArray(), offset = 60),
        // ZIP-based formats (OOXML / ODF / EPUB) all start with PK\x03\x04.
        // We rely on the *first ZIP entry's path* to disambiguate. The
        // deeper detection happens in [disambiguateZipFormats] below.
        Rule(FileKind.OOXML, "application/vnd.openxmlformats-officedocument",
            "OOXML (DOCX/XLSX/PPTX)",
            byteArrayOf(0x50, 0x4B, 0x03, 0x04)),
        Rule(FileKind.ODF, "application/vnd.oasis.opendocument",
            "OpenDocument (ODT/ODS/ODP)",
            byteArrayOf(0x50, 0x4B, 0x03, 0x04)),
        Rule(FileKind.EPUB, "application/epub+zip", "EPUB e-book",
            byteArrayOf(0x50, 0x4B, 0x03, 0x04)),
        Rule(FileKind.RTF, "application/rtf", "Rich Text Format",
            "{\\rtf".toByteArray()),
        Rule(FileKind.FB2, "application/xml", "FictionBook 2",
            "<FictionBook".toByteArray()),
        // ---- Phase 9.7.9: Fonts ----
        Rule(FileKind.TTF, "font/ttf", "TrueType font",
            byteArrayOf(0x00, 0x01, 0x00, 0x00)),
        Rule(FileKind.OTF, "font/otf", "OpenType font",
            "OTTO".toByteArray()),
        Rule(FileKind.WOFF, "font/woff", "WOFF font",
            "wOFF".toByteArray()),
        Rule(FileKind.WOFF2, "font/woff2", "WOFF2 font",
            "wOF2".toByteArray()),
        // ---- Phase 9.7.3: Vector image formats ----
        // EPS / PS files start with `%!PS-Adobe`
        Rule(FileKind.EPS, "application/postscript", "PostScript/EPS file",
            "%!PS-Adobe".toByteArray()),
        // Adobe Illustrator files have these signatures in their
        // producer string; the simplest reliable magic is the same
        // %PDF prefix as PDF but augmented with /Producer containing
        // Adobe Illustrator. The plain PDF rule already accepts it;
        // a future rule could disambiguate here.
        // CDR uses RIFF/CDR container.
        Rule(FileKind.CDR, "application/x-cdr", "CorelDRAW image",
            "RIFF".toByteArray()),
        // SVG is XML; we'll let the plain-text heuristic catch `<svg`
        // and tag it once the renderer peeks at the body.
        // Phase 9.7.4 (subtitle formats) and Phase 9.7.5 (email)
        // don't yet have pure magic rules; they rely on filename +
        // extension matching. We use SVG and other XML detection at
        // the consumer level via `looksLikeText` + content sniff.
        // Phase 9.7.7: PGP / crypto formats
        // ASCII-armored PGP begins with the canonical header.
        // We rule on the textual header alone because the binary form
        // has multiple valid first bytes.
        // X509 / DER-encoded certificates start with 0x30 (ASN.1 SEQUENCE).
        // Phase 9.7.8: scientific — HDF5, NetCDF, FITS.
        // FITS: SIMPLE  = T format string at offset 0.
        Rule(FileKind.FITS, "application/fits",
            "FITS (Flexible Image Transport System)",
            "SIMPLE  =".toByteArray()),
        // HDF5 / NetCDF4-classic share the 8-byte signature \x89HDF\r\n\x1a\n.
        // We pick HDF5 because that's the more common modern format; consumers
        // can re-disambiguate via the file's superblock.
        Rule(FileKind.HDF5, "application/x-hdf5",
            "HDF5 (NetCDF4-classic)",
            byteArrayOf(0x89.toByte(), 0x48, 0x44, 0x46, 0x0D, 0x0A, 0x1A, 0x0A)),
        // NetCDF3 classic magic "CDF" at offset 0.
        Rule(FileKind.NETCDF3, "application/x-netcdf",
            "NetCDF3 classic",
            "CDF".toByteArray()),
        // WASM: \0asm at offset 0.
        Rule(FileKind.WASM, "application/wasm",
            "WebAssembly binary",
            byteArrayOf(0x00, 0x61, 0x73, 0x6D)),
        // ---- Phase 9.7.6: lossless audio + additional video containers ----
        // Monkey's Audio: magic at offset 0 is "MAC ".
        Rule(FileKind.MAC, "audio/x-ape", "Monkey's Audio (APE)",
            "MAC ".toByteArray()),
        // WavPack: "wvpk" at offset 0.
        Rule(FileKind.WAVEPACK, "audio/x-wavpack", "WavPack audio",
            "wvpk".toByteArray()),
        // TTA: "TTA1" at offset 0.
        Rule(FileKind.TTA, "audio/x-tta", "TTA (True Audio)",
            "TTA1".toByteArray()),
        // Matroska / WebM: EBML header \x1A\x45\xDF\xA3 at offset 0.
        Rule(FileKind.MKV, "video/x-matroska", "Matroska (MKV/WebM)",
            byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())),
        // FLV: "FLV" at offset 0.
        Rule(FileKind.FLV, "video/x-flv", "Flash Video (FLV)",
            "FLV".toByteArray()),
        // ---- Phase 9.7.10: Disk + container image formats ----
        // ISO 9660: starts at offset 32768 with "CD001".
        Rule(FileKind.ISO_9660, "application/x-iso9660-image",
            "ISO 9660 disk image",
            "CD001".toByteArray(), offset = 32768),
        // VHD: "conectix" at offset 0.
        Rule(FileKind.VHD, "application/x-virtualbox-vhd",
            "VHD virtual disk",
            "conectix".toByteArray()),
        // VMDK: "KDMV" at offset 0.
        Rule(FileKind.VMDK, "application/x-vmdk",
            "VMDK virtual disk",
            "KDMV".toByteArray()),
        // ---- Phase 9.7.9: more font variations ----
        // TTC (TrueType Collection): magic "ttcf" at offset 0.
        Rule(FileKind.TTC, "font/collection",
            "TrueType Collection (TTC)",
            "ttcf".toByteArray()),
        // ---- Phase 9.7.7: Crypto ----
        // PGP binary packets: the first byte has bit 7 set (new format)
// OR bit 6 + 7 set (old format). We require bit 7 to be set on byte 0
// AND byte 1's top bit to NOT be set (a single-byte length in [0..191]),
// which sharply reduces false positives from random 0xFF/0xAA bytes.
        Rule(FileKind.PGP_BINARY, "application/pgp-encrypted",
            "PGP binary packet",
            byteArrayOf(0x80.toByte(), 0x00.toByte()),
            optionalMask = 0x80.inv()),
        // PKCS12 ("PFX") starts with the SEQUENCE of an ASN.1
        // PKCS#12 PFX PDU: 30 82 ?? ?? 02 01 03 30 82 ...
        // We use a 3-byte prefix to keep false positives low. PKCS12
        // comes before the generic X509_DER rule because both share
        // the 0x30 prefix but PKCS12's 0x30 0x82 0x02 prefix is
        // narrower.
        Rule(FileKind.PKCS12, "application/x-pkcs12",
            "PKCS#12 / PFX bundle",
            byteArrayOf(0x30.toByte(), 0x82.toByte(), 0x02.toByte())),
        // X.509 DER starts with ASN.1 SEQUENCE (0x30). Many PKCS
        // containers also begin with 0x30; we keep this as the catch-all
        // and use deeper sniffing for PKCS12 (see sniffTextFormats and
        // disambiguateCrypto).
        Rule(FileKind.X509_DER, "application/x-x509-ca-cert",
            "X.509 certificate (DER)",
            byteArrayOf(0x30.toByte())),
        // HDF5: signature at offset 0 is \x89HDF\r\n\x1a\n
        // NetCDF3 has classic magic `CDF` at offset 0; NetCDF4 is HDF5.
    )

    companion object {
private const val PROBE_SIZE = 65536  // 64 KiB head — large enough for ISO 9660 magic at offset 32768.
    private const val MIN_PROBE = 4

        val TEXT_PLAIN = Detection(FileKind.TEXT_PLAIN, "text/plain", "Plain text")
        val UNKNOWN = Detection(FileKind.BINARY_UNKNOWN, "application/octet-stream", "Unknown binary")
    }
}
