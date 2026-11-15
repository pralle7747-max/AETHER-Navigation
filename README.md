# Samsung Cam 2.0

Kamera-App für Android (Kotlin, CameraX, ML Kit) mit:

| Funktion | Umsetzung |
|---|---|
| **Follow** (an/aus) | Gesichtserkennung (ML Kit) → Ausschnitt (`SCALER_CROP_REGION`) schwenkt weich zum Motiv |
| **Auto-Zoom** (an/aus) | Zoom wird so geregelt, dass das Motiv ~45 % des Bildes füllt; ohne Motiv zurück auf 1× |
| Manueller Zoom | Pinch (wirkt, wenn Auto-Zoom aus ist) |
| **Horizont-Lock** (an/aus) | Gravitationssensor → Vorschau und Foto werden gegen die Neigung gedreht (±45°), Wasserwaage im Bild |
| **Galerie** | Alle Fotos des Geräts als Raster, Antippen öffnet den Editor |
| **Editor: Radieren** | Objekt übermalen → „Entfernen“ (klassisches Inpainting, kein generatives Modell) |
| **Editor: Verschieben** | Objekt umfahren → ausschneiden → mit dem Finger verschieben → anwenden (Lücke wird aufgefüllt) |
| **Editor: Malen** | Freihand zeichnen, 7 Farben, Zurück-Funktion |

Fotos landen in `Pictures/SamsungCam2`.

## APK
`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (debug-signiert, direkt installierbar;
bei Android „Unbekannte Apps installieren“ erlauben). Alternativ baut GitHub Actions die APK als Artifact.

## Bauen
Projekt in Android Studio öffnen (JDK 17, Android SDK 34) und auf ein Gerät mit Android 10+ starten.
Unit-Tests der Follow/Auto-Zoom-Logik: `./gradlew :app:testDebugUnitTest`.

## Stand / Grenzen
- Die App kompiliert, die APK wird gebaut, und Follow-/Auto-Zoom-Logik sowie Inpainting sind per Unit-Test
  geprüft. Kamera, Sensor, Galerie und Editor-Bedienung wurden **noch nicht auf einem Gerät getestet**.
- Verschieben: Objekt wird als gemalte Fläche ausgeschnitten (nicht pixelgenau), kein Skalieren/Drehen.
- Das Vorzeichen des Horizont-Winkels und das Verhalten von `SCALER_CROP_REGION` können je nach
  Gerät abweichen – ggf. in `MainActivity.applyHorizonToPreview` bzw. `levelHorizon` anpassen.
- Follow/Auto-Zoom erkennen Gesichter (Personen). Andere Objekte: ML Kit Object Detection ergänzen.
- Der Radierer funktioniert für kleine Objekte; für große Flächen ein ML-Modell (z. B. LaMa/TFLite) einbauen.
- Eine Samsung-eigene Kamera-App (One UI) lässt sich so nicht ersetzen; das hier ist eine eigenständige App.
