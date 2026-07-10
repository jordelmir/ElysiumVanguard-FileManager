# Elysium Vanguard — Privacy Policy

**Last updated:** 2026-07-09
**Effective:** 2026-07-09

Elysium Vanguard is a local file manager. This policy explains exactly what
data the app handles, where that data lives, and what leaves your device.

## TL;DR

- The app stores everything on your device.
- The app does **not** transmit your files, names, paths, or any other
  personal data off your device.
- The app has **no analytics SDK, no advertising SDK, no crash reporting
  SDK, and no third-party telemetry**.
- The only network code in the app is the optional SFTP server and the
  optional local HTTP server, both of which **only listen on your local
  network** (LAN). They do not connect to the internet.
- The app's auto-backup to Google Drive is opt-in at the OS level and excludes
  sensitive paths (see "Backup" below).

## Data we handle

| Data | Where it lives | Who can see it |
|---|---|---|
| File metadata (name, size, type) | Your device, in our Room database | You, the app |
| Vault encrypted files (when you use Vault) | Your device, in `files/vault/` | You, the app |
| Vault metadata (which files are encrypted) | Your device, in Room | You, the app |
| SFTP server host key | Your device, in `files/sftp-hostkey/` | You, the app, anyone on your LAN with the password |
| OCR results (text extracted from images) | Your device, in `files/ocr/` | You, the app |
| Tags, colors, notes you set on files | Your device, in Room | You, the app |
| Smart folders you created | Your device, in Room | You, the app |
| Trashed files (files you deleted via the app) | Your device, in `files/trash/` | You, the app, until auto-purge |
| Your preferences (theme, view mode) | Your device, in SharedPreferences | You, the app |

**Nothing in this list leaves your device.**

## Local network servers (optional)

The app can run two servers on your device, both entirely under your control:

### Local HTTP server
- Listens on `0.0.0.0:8765` (configurable)
- Serves a single-page web UI for browsing/downloading/uploading files
- Authenticated by a one-time bearer token shown as a QR code
- Only accessible from devices on the same Wi-Fi network as your phone
- **Does not connect to the internet**

### SFTP server
- Listens on `0.0.0.0:2222` (configurable)
- Standard SSH/SFTP protocol — connect from any SSH client (terminal,
  FileZilla, Cyberduck, etc.)
- Authenticated by username + auto-rotated password
- Only accessible from devices on the same Wi-Fi network
- **Does not connect to the internet**

Both servers are off by default. You start them from the file manager's
tools menu, and they stop when you tap "Stop" or kill the app.

## Backup

Android's auto-backup feature (Settings → System → Backup) can upload app
data to Google Drive. The default behavior in modern Android is to back up
**only the data we explicitly allow** in our backup rules.

We **exclude** the following from any auto-backup:
- SFTP host key (private key material)
- Vault encrypted payloads
- OCR output (extracted text from your documents)
- Trash contents (until purged)

We **include** the following so your settings carry across reinstalls:
- App preferences (theme, view mode, recent folders)
- Tags, colors, notes you set on files
- Smart folders you created
- Vault **metadata** (which files are encrypted, but not the ciphertext)

**You can disable auto-backup entirely** at the OS level (Settings → System
→ Backup → toggle off). We respect whatever you choose.

## Permissions

The app requests the following permissions, and only these:

| Permission | Why | What happens if you deny it |
|---|---|---|
| `INTERNET` | Required for the optional HTTP and SFTP servers | The local servers won't run. Everything else works. |
| `READ_MEDIA_AUDIO/VIDEO/IMAGES` (Android 13+) | Find media files in the system gallery | The file manager can only see files you grant via the SAF picker |
| `READ_EXTERNAL_STORAGE` (Android ≤ 12) | Read user files on older Android | Same as above for old Android versions |
| `POST_NOTIFICATIONS` | Show progress for long operations | Progress notifications don't appear; ops still complete |
| `WAKE_LOCK` | Keep the device awake during long ZIP operations | ZIPs may pause if the device sleeps |
| `FOREGROUND_SERVICE` | Run long operations in the background | ZIPs/HTTP server stop when you background the app |
| `FOREGROUND_SERVICE_DATA_SYNC` | Same as above, with the correct type for Android 14+ | Same as above |

We do **not** request:
- `MANAGE_EXTERNAL_STORAGE` ("All Files Access")
- `READ_PHONE_STATE`
- `ACCESS_FINE_LOCATION` or any location permission
- `CAMERA` (the OCR feature accepts images you pick, not camera capture)
- `RECORD_AUDIO`
- `READ_CONTACTS`, `READ_SMS`, `READ_CALL_LOG`

## Children's privacy

The app is not directed to children under 13. We do not knowingly collect
any data from children.

## Changes to this policy

If we change this policy materially (e.g. we add a third-party SDK), we
will:
- Update the "Last updated" date above
- Surface a notice in the app's settings screen
- For changes that affect what data leaves the device, require explicit
  re-consent

## Contact

For privacy questions, contact the developer at the email address listed on
the app's Google Play listing (or F-Droid listing, GitHub releases, etc.).

## License

The app is distributed under [license TBD]. The source code is available
at the repository link in the app's settings.

---

**This document is part of the Elysium Vanguard app source. Last verified
against commit: TBD.**
