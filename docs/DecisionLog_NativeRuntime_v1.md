# LOBBYISM — Decision Log: Native Runtime (Unity-Ablösung)

**Version:** 1.0
**Datum:** 16. November 2026
**Status:** Entschieden für Phase 0a. Offene Punkte am Ende.
**Autorität:** Überschreibt den Abschnitt „Tech Stack" und die Client-Struktur in `CLAUDE.md` sowie Engine-Angaben in GDD v2–v4. Gameplay-Regeln (R1–R6), Node-Datenmodell und Phasenplan bleiben unverändert gültig.

---

## ENTSCHIEDEN — N1: Keine Unity-Engine, eigene Mikro-Runtime

**Problem:** Unity ist proprietär, an einen Editor gebunden und für Coding-Agenten schlecht automatisierbar (Szenen, Prefabs). Ziel ist Unabhängigkeit von Firmen und ein vollständig offener Code.

**Entscheidung:**

- Der Client ist eine native Android-App in Kotlin. Kein Unity, kein AR Foundation.
- Rendering über eine eigene, kleine Runtime auf OpenGL ES 2.0. Sie kann genau drei Dinge: Kamerabild, Punktwolken, Signal-Degradations-Shader.
- Die App hat **keine** Bibliotheks-Abhängigkeiten. Nur Android-Framework und Kotlin-Standardbibliothek.
- Der bisherige Unity-Prototyp wird nicht weitergeführt.

**Konsequenz:** Das Projekt ist reiner Text-Code. Claude Code kann bauen, installieren, Logs lesen und korrigieren, ohne Editor.

---

## ENTSCHIEDEN — N2: Tracking in Stufen, hinter einer Schnittstelle

**Problem:** ARCore (Google, Closed Source) ist die eigentliche Fremdabhängigkeit, nicht die Engine.

**Entscheidung:**

- **Stufe 1 (umgesetzt):** 3DoF. Orientierung aus `GAME_ROTATION_VECTOR` (Gyro + Beschleunigung, ohne Magnetometer, R2). Position aus GPS direkt vom Empfänger (`GPS_PROVIDER`, ohne Google Play Services). Virtueller Boden aus Schwerkraft und angenommener Haltehöhe (Config: `cameraHeightM`).
- **Stufe 2 (geplant):** OpenVINS für echte Bodenverankerung, mit Kalibrier-Ritual und Community-Kalibrierdatenbank.
- ARCore höchstens als optionales Plugin, nie als Voraussetzung.

**Konsequenz:** In Stufe 1 gibt es keine Verdeckung durch echte Wände und keine Parallaxe beim Gehen im Nahbereich.

---

## ENTSCHIEDEN — N3: Norden ohne Kompass

**Problem:** R2 verbietet das Magnetometer. Der Gyro-Sensor hat aber keine absolute Nullrichtung.

**Entscheidung:**

- Die Nullrichtung wird aus dem GPS-Kurs beim Gehen geschätzt (`HeadingEstimator`, zirkulärer gleitender Mittelwert).
- Bis genug übereinstimmende Messungen vorliegen, zeigt das HUD „NORD SYNC" und ferne Nodes werden nicht gezeichnet.
- Nodes im Nahbereich brauchen keinen Norden (siehe N4).

**Konsequenz:** Fern-Anzeige funktioniert erst nach einigen Schritten geradeaus. Alle Schwellen stehen in `config.json`.

---

## ENTSCHIEDEN — N4: Materialisierung durch „Pinning"

**Problem:** GPS springt um 4–28 m. Ein Node in 2 m Entfernung würde direkt aus GPS-Daten um den Spieler herumspringen.

**Entscheidung:**

- Innerhalb des Materialisierungs-Radius wird der Node an einen festen Punkt im Sensor-Raum geheftet. Danach bewegt ihn nur noch der Gyro.
- Radius = `materializeRadiusM` + aktuelle GPS-Ungenauigkeit, gedeckelt durch `materializeRadiusMaxM`. Schlechtes GPS vergrößert den Radius, es sperrt niemanden aus (R1).
- Verlassen erst jenseits Radius × `materializeHysteresis`.

**Abweichung vom GDD v4:** Dort ist Stufe 4 mit ~0–3 m angegeben und in Phase 0a die einzige aktive Stufe. Umgesetzt sind 8 m plus GPS-Fehler sowie zusätzlich die Himmelssäule (Stufe 2).

- Regel: nur Stufe 4, 0–3 m.
- Wirkung des Bruchs: Nodes sind mit echtem GPS überhaupt erreichbar und aus der Ferne auffindbar.
- Passt zur Absicht: ja für die Testbarkeit. Die Zahlen sind Config-Werte und nach Feldtests anzupassen.

---

## ENTSCHIEDEN — N5: Offline-first, Server optional

**Entscheidung:**

- Ein Node wird sofort lokal gespeichert (SQLite) und später hochgeladen.
- Ohne eingetragene Server-Adresse arbeitet die App rein lokal.
- Server bleibt Nakama + PostGIS (wie bisher). Zugriff über die öffentliche REST-Schnittstelle, ohne SDK.
- Die Node-ID erzeugt der Client. Doppeltes Senden ist folgenlos.
- Die Geräte-ID ist ein Zufallswert, keine Hardware-ID.

**Konsequenz:** Phase 0a ist auf einem Handy ohne Server testbar. Sichtbarkeit auf einem zweiten Handy braucht einen erreichbaren Server.

---

## ENTSCHIEDEN — N6: Build-First-Systeme übernommen

| System aus `CLAUDE.md` | Umsetzung nativ |
|---|---|
| SensoryFeedback.cs | `feedback/SensoryFeedback.kt` — zentraler Dispatcher für Haptik, Ton, Glitch |
| GlitchController.cs | Composite-Shader in `render/Shaders.kt`, ausgelöst durch Feedback-Cues |
| Canvas-Hierarchie | noch offen — aktuell HUD, Content-Karte, Blocker als drei Ebenen |

---

## Zusammenfassung

| # | Entscheidung | Status |
|---|---|---|
| N1 | Native Kotlin-App mit eigener Mikro-Runtime statt Unity | ✅ umgesetzt |
| N2 | Tracking Stufe 1 (3DoF + GPS + virtueller Boden) | ✅ umgesetzt, ungetestet am Gerät |
| N2 | Tracking Stufe 2 (OpenVINS) | geplant |
| N3 | Norden aus GPS-Kurs statt Kompass | ✅ umgesetzt, ungetestet am Gerät |
| N4 | Pinning + Himmelssäule | ✅ umgesetzt, ungetestet am Gerät |
| N5 | Offline-first, Nakama über REST | ✅ Client umgesetzt; Server nur gegen Attrappe getestet |
| N6 | SensoryFeedback + Glitch | ✅ umgesetzt |

## Offene Punkte

| # | Punkt | Nötig vor |
|---|---|---|
| O1 | Lizenz. OpenVINS (GPLv3) würde den Client zu GPLv3 machen. `CLAUDE.md` sieht bisher MIT/Apache (Client) und BSL 1.1 (Server) vor. BSL ist keine Open-Source-Lizenz nach OSI. | Veröffentlichung des Repos |
| O2 | Gerätetest Pixel 9 Pro und Galaxy A50: Kamerabild, Ausrichtung, Lock-Gefühl, Framerate | nächster Schritt |
| O3 | Server live (Hetzner), TLS davor | Test mit zweitem Handy |
| O4 | Moderation und DSA-Meldeweg | Öffnung für fremde Creator |
| O5 | Parallaxe im Nahbereich (Schritterkennung oder Stufe 2) | Phase 0b |

---

*v1.0 — 16.11.2026 — Erstfassung nach Umstellung auf native Runtime.*
