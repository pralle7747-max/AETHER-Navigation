# AETHER – PC-App (Windows)

Hülle mit Electron 33.4.11 (win32-x64) um dieselbe Web-App wie in der APK (`assets/public`).

- `main.js` – lädt die App über ein eigenes Schema `aether://`, erlaubt Standort/Mikrofon/Benachrichtigungen, öffnet externe Links im Browser, F11 = Vollbild, F5 = neu laden. Standort unter Windows über den Windows-Standortdienst.
- `seticon.js` – setzt Symbol und Versionsinfo in `AETHER.exe` (npm-Pakete `resedit`, `pe-library`).

Aufbau des Pakets: `electron.exe` → `AETHER.exe`, App-Dateien unter `resources/app/` (`main.js`, `package.json`, `icon.png`, `www/` = Web-App).
