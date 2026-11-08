# TV Debloat + Gallery Home

Private kit for my Panasonic Android TV (SCBC R4, Android TV 11).

- **Debloat:** ads, telemetry and shop-demo apps switched off with `pm disable-user` (never uninstalled), every change logged so it can be undone.
- **Gallery Home:** my own home screen. Logo Line dock, Big Shoulders clock, rotating wallpapers (space, night cities, dark landscapes), HKO weather and warnings, a Nest Mini mini player over Google Cast, and a live **Commute** board (Citybus A11/A12/B13, MTR Island Line and the TKO → Yau Tong → Kowloon Tong journey, North Point ferries).

Full usage notes are in **README.txt**.

## What's here

| Path | What it is |
|---|---|
| `tv.sh` | Everything over adb: `connect`, `backup`, `shot`, `disable`, `enable`, `install`, `apply <plan>`, `diag`, `doctor` |
| `restore.sh` | Undo everything from the backup's `changes.log` (`--keep-launcher`, `--dry-run`) |
| `plan.txt`, `update-*.txt` | Step-by-step plans run with `tv.sh apply`, one per change, in history order |
| `GalleryHome.apk` | The current build (5.5.1) |
| `launcher/` | Gallery Home source (Java, no Gradle; built with `launcher/build.sh` on Debian/Ubuntu: aapt, dx, zipalign, apksigner) |

## Not in this repo (on purpose)

- `platform-tools/` (Google's adb), `tv-backup-*/` (device dumps and screenshots), `.tv_target` (TV IP).
- `signing/galleryhome-signing.jks`: the key Gallery Home is signed with. **Keep a copy somewhere safe** (password manager, encrypted drive). Without it, a newer Gallery Home can't be installed over the current one; you'd have to uninstall it first and lose its settings. Store password and key password: `galleryhome`, alias `galleryhome`. To build, copy it to `launcher/keystore.jks`.

## Privacy

The commute code contains my home location (for walking times). Keep this repo **private**.
