# Screenshots

This directory contains screenshots of NetValve's main screens.

## How to Add Screenshots

### From an Android Device or Emulator

1. Build and install the APK:
   ```bash
   ./gradlew :app:assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

2. Launch the app and navigate to each screen.

3. Take a screenshot:
   - **Device**: Press Power + Volume Down simultaneously
   - **Emulator**: Click the camera icon in the emulator toolbar, or `adb exec-out screencap -p > screen.png`

4. Pull the screenshot to your computer:
   ```bash
   adb pull /sdcard/Pictures/Screenshots/screen.png
   ```

5. Optimize and place in this directory:
   ```bash
   # Optional: optimize PNG size
   optipng -o7 screen.png

   # Rename and place
   mv screen.png docs/screenshots/dashboard.png
   ```

## Recommended Screenshots

Please capture the following screens (in light AND dark theme if possible):

| Filename | Screen | What to Show |
|----------|--------|--------------|
| `dashboard.png` | Dashboard | Tunnel ON, live stats visible, several controlled apps |
| `apps-selection.png` | App Selection | Search bar active, multiple apps selected, system app filter |
| `app-detail.png` | Per-App Detail | Download/Upload caps set, schedule configured, conditions enabled |
| `stats.png` | Statistics | Live throughput graph, per-app totals, connection counts |
| `logs.png` | Logs | Multiple log levels visible, filter active |

## Size Recommendations

- **Resolution**: 1080×1920 or higher (device native)
- **Format**: PNG (lossless) or WebP (smaller)
- **File size**: Keep under 500 KB per screenshot (use `optipng` or `pngquant`)
- **Aspect ratio**: Portrait (9:16 or 9:19.5 for modern devices)

## Privacy Note

Before sharing screenshots:
- ✅ Ensure no personal app names are visible (or blur them)
- ✅ Check that no notifications with sensitive data are showing
- ✅ Use the "screen capture protection" in the app if needed

## Embedding in README

Once added, reference them in the main `README.md`:

```markdown
### Dashboard
![Dashboard](docs/screenshots/dashboard.png)
```
