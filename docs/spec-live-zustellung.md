# Live-Zustellung — Spezifikation

## 1. Zweck und Abgrenzung
<!-- Was die Live-Zustellung tut, in 2–3 Sätzen. -->
<!-- Was nicht dazugehört (siehe PDF: Keycloak, web-gateway, React, Räume anlegen ...). -->

## 2. Vertrag
### 2.1 Was am Exchange ankommt
<!-- Name, Typ, wer publiziert, Format der Nachricht. Beleg: Datei/Methode oder Messung. -->
### 2.2 Was am Stream hinausgeht
<!-- Endpunkt, Parameter, Content-Type, Aufbau eines Ereignisses (id:, data:), Kommentare. -->
<!-- Was passiert bei fehlender oder ungültiger roomId? -->

## 3. Queues
| Queue | Name | Lebensdauer | Grenze | Was an der Grenze passiert | Begründung |
|---|---|---|---|---|---|
| Live-Queue pro Instanz |  |  |  |  |  |
| chat.persist (bleibt) |  |  |  |  |  |

## 4. Verhalten
### 4.1 Normalfall: der Weg einer Nachricht vom POST bis zum data:
### 4.2 Zwei Instanzen (S3)
### 4.3 Zurück auf eine Instanz (S4)
### 4.4 Datenbank und batch-writer weg (S5)
### 4.5 Hängende Instanz (S6)
### 4.6 Neuverbindung mit Last-Event-ID (S7)
<!-- Jeweils: was passiert, und warum so. -->

## 5. Abnahmekriterien
| Nr | Kriterium | Befehl | Erwartet |
|---|---|---|---|
| S1 |  | `mvn clean test` |  |
| ... |  |  |  |

## 6. Entscheide und offene Punkte
| Entscheid | Begründung | Verworfen |
|---|---|---|
