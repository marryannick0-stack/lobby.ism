# LOBBYISM

Ortsbasierte AR-Plattform. Inhalte werden an echten Orten hinterlassen und durch die Kamera gefunden.

**Stand: Phase 0a.** Eine Textnachricht an einem echten Ort ablegen, wiederfinden, per Gyro-Lock öffnen.

## Aufbau

| Ordner | Inhalt |
|---|---|
| `android/` | Die App. Kotlin, eigene Render-Runtime auf OpenGL ES 2.0, keine Bibliotheken. |
| `server/` | Nakama-Modul (TypeScript) und Docker-Setup mit PostGIS. |
| `docs/` | Decision Logs und `ARCHITECTURE.md` (welches Feld in welcher Datei liegt). |
| `CLAUDE.md`, `.claude/rules/` | Arbeitsanweisungen für Claude Code: Befehle, Aufbau, Regeln. |
| `.github/workflows/` | Baut bei jedem Push automatisch die Test-App. |

## App aufs Handy

Jeder Push auf `main` baut eine APK. Sie liegt danach unter **Releases** als „Testbuild N".
Auf dem Handy herunterladen, öffnen, Installation aus dieser Quelle erlauben.
Neue Builds lassen sich über alte installieren, die Daten bleiben erhalten.

## Bedienung

1. Ins Freie gehen, bis oben `SIGNAL` steht.
2. `+ HINTERLASSEN` tippen, Text eingeben, ablegen. Der Node erscheint vor dir.
3. Mit dem Fadenkreuz auf den Node zielen und halten. Vier Ticks, dann rastet der Lock ein und der Text erscheint.
4. Für ferne Nodes ein Stück geradeaus gehen, bis `NORD FIX` steht. Dann zeigen Säulen die Richtung.

Alle Stellwerte (Radien, Lock-Dauer, Höhen) stehen in `android/app/src/main/assets/config.json`.

## Server starten

Nur nötig, damit ein zweites Handy dieselben Nodes sieht.

```
cd server
cp .env.example .env      # jeden Wert durch einen eigenen Zufallswert ersetzen
npm install
npm run build
docker compose up -d
```

In der App unter `≡` die Adresse (`http://SERVER-IP:7350`) und den `NAKAMA_SERVER_KEY` eintragen.
Testbuilds erlauben `http://`. Für alles Öffentliche gehört ein TLS-Proxy davor.

## Selbst bauen

Voraussetzungen: JDK 17, Android SDK (Plattform 35), Gradle 8.10.

```
cd android
gradle testDebugUnitTest lintDebug assembleDebug

cd ../server
npm ci && npm run build && npm test
```

## Lizenz

Noch nicht festgelegt, siehe Decision Log, Punkt O1. Bis dahin ist das Repository nicht zur Weitergabe freigegeben.
