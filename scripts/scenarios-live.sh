#!/usr/bin/env bash
# Stellt die Szenarien S2 bis S8 der Live-Zustellung nach (docs/spec-live-zustellung.md, Abschnitt 5).
#
# Aufruf im Wurzelverzeichnis des Repos:   bash scripts/scenarios-live.sh
# Voraussetzung: Docker läuft und .env existiert (cp .env.example .env).
# S1 ist "mvn clean test" und läuft getrennt davon.
#
# Die SSE-Clients sind curl-Container im Netz chat-net, weil kein Dienst einen
# Port auf den Host veröffentlicht. Jeder Client schreibt den Stream in sein
# Container-Log; ausgezählt wird mit "docker logs". Jede Nachricht hat einen
# eindeutigen Text "<Szenario>-<Lauf>-...", damit ältere Läufe nicht mitzählen.
# Dauer: etwa 8 bis 10 Minuten, davon gut 2 Minuten für die lange Pause in S6.

set -u

RUN_ID=$(date +%s)
FAILED=0
CLIENTS=""

# ---------- Hilfsfunktionen ----------

# Gibt eine Zeile mit OK oder FEHLER aus und merkt sich Fehler für den Schluss.
report() {
  local scenario="$1" ok="$2" detail="$3"
  if [ "$ok" = "yes" ]; then
    echo "  $scenario OK      $detail"
  else
    echo "  $scenario FEHLER  $detail"
    FAILED=1
  fi
}

# Eine neue Raum-ID. Jedes Szenario hat seine eigenen Räume. Der Linux-Kern im
# rabbitmq-Container liefert sie, so klappt es auch unter Git Bash auf Windows.
new_room() {
  docker compose exec -T rabbitmq cat /proc/sys/kernel/random/uuid | tr -d '[:space:]'
}

# Führt eine SQL-Abfrage in der Chat-Datenbank aus, von innen im postgres-Container.
sql() {
  docker compose exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -tAc \"$1\"" | tr -d '[:space:]'
}

# Alle Queues mit Nachrichtenzahl und Verbrauchern.
list_queues() {
  docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages consumers
}

# Wie viele Live-Queues es gibt.
live_queue_count() {
  list_queues | grep -c '^chat\.live\.'
}

# Wie viele Live-Queues am Exchange chat.delivery hängen.
live_binding_count() {
  docker compose exec -T rabbitmq rabbitmqctl -q list_bindings source_name destination_name \
    | awk '$1 == "chat.delivery" && $2 ~ /^chat\.live\./' | wc -l | tr -d ' '
}

# Wartet höchstens $2 Sekunden, bis es genau $1 Live-Queues mit je einem Verbraucher gibt.
wait_for_live_queues() {
  local expected="$1" timeout="$2" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    local count consumers
    count=$(live_queue_count)
    consumers=$(list_queues | awk '$1 ~ /^chat\.live\./ && $3 == 1' | wc -l | tr -d ' ')
    if [ "$count" = "$expected" ] && [ "$consumers" = "$expected" ]; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

# Sekunden seit dem Start des Linux-Kerns, auf dem die Container laufen. Für
# Zeitmessungen statt der Uhrzeit: Stellt der Rechner mitten im Lauf seine Uhr
# um, wäre die Differenz zweier Uhrzeiten falsch. Dieser Zähler springt nie.
uptime_seconds() {
  docker compose exec -T rabbitmq cut -d. -f1 /proc/uptime | tr -d '[:space:]'
}

# Die IP-Adresse eines Containers im Netz chat-net.
ip_of() {
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$1"
}

# Startet einen SSE-Client als Container: $1 Name, $2 IP der Instanz, $3 Raum, $4 Last-Event-ID (optional).
# Kehrt zurück, sobald der Kommentar "verbunden" angekommen ist.
start_client() {
  local name="$1" ip="$2" room="$3" last_id="${4:-}" waited=0
  if [ -n "$last_id" ]; then
    docker run -d --name "$name" --network chat-net curlimages/curl -sN \
      -H 'Accept: text/event-stream' -H "Last-Event-ID: $last_id" \
      "http://$ip:8080/stream?roomId=$room" >/dev/null
  else
    docker run -d --name "$name" --network chat-net curlimages/curl -sN \
      -H 'Accept: text/event-stream' \
      "http://$ip:8080/stream?roomId=$room" >/dev/null
  fi
  CLIENTS="$CLIENTS $name"
  while ! docker logs "$name" 2>/dev/null | grep -q '^:verbunden' && [ "$waited" -lt 30 ]; do
    sleep 1
    waited=$((waited + 1))
  done
}

# Wie viele Nachrichten ein Client bekommen hat (Zeilen "id:").
events_of() {
  docker logs "$1" 2>/dev/null | grep -c '^id:'
}

# Wie viele verschiedene Nachrichten ein Client bekommen hat.
distinct_events_of() {
  docker logs "$1" 2>/dev/null | grep '^id:' | sort -u | wc -l | tr -d ' '
}

# Die IDs in der Reihenfolge, in der ein Client sie bekommen hat.
ids_of() {
  docker logs "$1" 2>/dev/null | grep '^id:' | cut -c4-
}

# Zählt Verstösse gegen die Reihenfolge pro Absender. Der Text endet auf "-<Nummer>",
# und pro Absender muss die Nummer bei 1 beginnen und um je 1 steigen.
order_violations() {
  docker logs "$1" 2>/dev/null | grep '^data:' \
    | sed -E 's/.*"senderId":"([^"]*)".*"content":"[^"]*-([0-9]+)".*/\1 \2/' \
    | awk '{ expected = ($1 in last) ? last[$1] + 1 : 1; if ($2 != expected) bad++; last[$1] = $2 } END { print bad + 0 }'
}

# Wartet höchstens $3 Sekunden, bis Client $1 mindestens $2 Nachrichten hat.
wait_for_events() {
  local name="$1" expected="$2" timeout="$3" waited=0
  while [ "$(events_of "$name")" -lt "$expected" ] && [ "$waited" -lt "$timeout" ]; do
    sleep 1
    waited=$((waited + 1))
  done
}

# Schickt $4 Nachrichten über POST /messages an die Instanz $1, Raum $2, Absender $3.
# Der Text ist "<Präfix><Nummer>", die Nummer zählt ab 1.
send_messages() {
  local ip="$1" room="$2" sender="$3" count="$4" prefix="$5"
  docker run --rm --network chat-net curlimages/curl sh -c "
    for i in \$(seq 1 $count); do
      curl -s -o /dev/null -X POST http://$ip:8080/messages \
        -H 'Content-Type: application/json' \
        -d \"{\\\"roomId\\\":\\\"$room\\\",\\\"senderId\\\":\\\"$sender\\\",\\\"senderName\\\":\\\"$sender\\\",\\\"content\\\":\\\"$prefix\$i\\\"}\"
    done"
}

# Anzahl Zeilen in message, deren Text mit dem Präfix beginnt.
count_prefix() {
  sql "SELECT count(*) FROM message WHERE content LIKE '$1%'"
}

# Wartet höchstens $3 Sekunden, bis mindestens $2 Zeilen mit dem Präfix $1 gespeichert sind.
wait_for_prefix() {
  local prefix="$1" expected="$2" timeout="$3" waited=0 rows
  rows=$(count_prefix "$prefix" 2>/dev/null)
  while [ "${rows:-0}" -lt "$expected" ] && [ "$waited" -lt "$timeout" ]; do
    sleep 2
    waited=$((waited + 2))
    rows=$(count_prefix "$prefix" 2>/dev/null)
  done
  echo "${rows:-0}"
}

# Entfernt alle SSE-Client-Container dieses Laufs.
remove_clients() {
  if [ -n "$CLIENTS" ]; then
    docker rm -f $CLIENTS >/dev/null 2>&1
  fi
  CLIENTS=""
}
trap remove_clients EXIT

# ---------- S2: Stack läuft, kein Port veröffentlicht ----------
echo "S2: Stack starten"
docker compose up -d --build >/dev/null 2>&1
wait_for_live_queues 1 180
running=$(docker compose ps --format '{{.Service}} {{.State}}' | grep -c ' running')
services=$(docker compose config --services | wc -l | tr -d ' ')
published=$(docker compose ps --format '{{.Ports}}' | grep -c -- '->')
if [ "$running" = "$services" ] && [ "$published" = "0" ]; then
  report S2 yes "$running von $services Diensten laufen, 0 veröffentlichte Ports"
else
  report S2 no "$running von $services Diensten laufen, $published Dienste mit veröffentlichtem Port"
fi

# ---------- S3: zwei Instanzen, zwei Clients in Raum A, einer in Raum B ----------
echo "S3: auf zwei Instanzen skalieren, je 200 Nachrichten über jede Instanz"
docker compose up -d --scale chat-service=2 >/dev/null 2>&1
wait_for_live_queues 2 120
set -- $(docker compose ps -q chat-service)
ip_a=$(ip_of "$1")
ip_b=$(ip_of "$2")
room_a=$(new_room)
room_b=$(new_room)
start_client "s3-$RUN_ID-a-at-1" "$ip_a" "$room_a"
start_client "s3-$RUN_ID-a-at-2" "$ip_b" "$room_a"
start_client "s3-$RUN_ID-b-at-1" "$ip_a" "$room_b"
send_messages "$ip_a" "$room_a" "s3a-$RUN_ID" 200 "S3-$RUN_ID-a-" &
send_messages "$ip_b" "$room_a" "s3b-$RUN_ID" 200 "S3-$RUN_ID-b-" &
wait
wait_for_events "s3-$RUN_ID-a-at-1" 400 30
wait_for_events "s3-$RUN_ID-a-at-2" 400 30
sleep 2
got_1=$(events_of "s3-$RUN_ID-a-at-1")
got_2=$(events_of "s3-$RUN_ID-a-at-2")
unique_1=$(distinct_events_of "s3-$RUN_ID-a-at-1")
unique_2=$(distinct_events_of "s3-$RUN_ID-a-at-2")
order_1=$(order_violations "s3-$RUN_ID-a-at-1")
order_2=$(order_violations "s3-$RUN_ID-a-at-2")
got_b=$(events_of "s3-$RUN_ID-b-at-1")
if [ "$got_1" = "400" ] && [ "$got_2" = "400" ] && [ "$unique_1" = "400" ] && [ "$unique_2" = "400" ] \
   && [ "$order_1" = "0" ] && [ "$order_2" = "0" ] && [ "$got_b" = "0" ]; then
  report S3 yes "beide Clients in Raum A: 400, keine doppelt, Reihenfolge pro Absender stimmt; Raum B: 0"
else
  report S3 no "Raum A an Instanz 1: $got_1 ($unique_1 verschieden, $order_1 Reihenfolgefehler), an Instanz 2: $got_2 ($unique_2, $order_2); Raum B: $got_b"
fi
remove_clients

# ---------- S4: zurück auf eine Instanz ----------
echo "S4: zurück auf eine Instanz"
bindings_before=$(live_binding_count)
# Gezählt wird ab dem Befehl, der skaliert, also mit der Zeit für das Stoppen
# des Containers.
started=$(uptime_seconds)
docker compose up -d --scale chat-service=1 >/dev/null 2>&1
waited_ok=no
elapsed=0
while [ "$elapsed" -le 30 ]; do
  if [ "$(live_queue_count)" = "1" ]; then
    waited_ok=yes
    break
  fi
  sleep 1
  elapsed=$(( $(uptime_seconds) - started ))
done
gone_after=$(( $(uptime_seconds) - started ))
queues=$(list_queues)
live_consumers=$(echo "$queues" | awk '$1 ~ /^chat\.live\./ { print $3 }')
has_persist=$(echo "$queues" | grep -c '^chat\.persist')
has_dlq=$(echo "$queues" | grep -c '^chat\.dlq')
bindings_after=$(live_binding_count)
if [ "$bindings_before" = "2" ] && [ "$waited_ok" = "yes" ] && [ "$bindings_after" = "1" ] \
   && [ "$live_consumers" = "1" ] && [ "$has_persist" = "1" ] && [ "$has_dlq" = "1" ]; then
  report S4 yes "vorher 2 Live-Queues, nach $gone_after s nur noch 1 mit 1 Verbraucher; chat.persist und chat.dlq da"
else
  report S4 no "vorher $bindings_before Bindings, nachher $bindings_after nach $gone_after s, Verbraucher $live_consumers, persist $has_persist, dlq $has_dlq"
fi

# ---------- S5: batch-writer und postgres weg, Live-Weg läuft weiter ----------
echo "S5: batch-writer und postgres stoppen, 100 senden"
ip=$(ip_of "$(docker compose ps -q chat-service)")
room=$(new_room)
prefix="S5-$RUN_ID-"
docker compose stop batch-writer postgres >/dev/null 2>&1
start_client "s5-$RUN_ID" "$ip" "$room"
sent_at=$(uptime_seconds)
send_messages "$ip" "$room" "s5-$RUN_ID" 100 "$prefix"
wait_for_events "s5-$RUN_ID" 100 5
live_got=$(events_of "s5-$RUN_ID")
live_seconds=$(( $(uptime_seconds) - sent_at ))
docker compose start postgres batch-writer >/dev/null 2>&1
rows=$(wait_for_prefix "$prefix" 100 90)
if [ "$live_got" = "100" ] && [ "$rows" = "100" ]; then
  report S5 yes "100 live beim Client (Senden und Zustellen zusammen $live_seconds s), nach dem Neustart 100 in message"
else
  report S5 no "$live_got live beim Client, $rows in message"
fi
remove_clients

# ---------- S6: eine Instanz hängt ----------
echo "S6: zwei Instanzen, eine pausieren, 5000 über die andere senden"
docker compose up -d --scale chat-service=2 >/dev/null 2>&1
wait_for_live_queues 2 120
set -- $(docker compose ps -q chat-service)
paused_id="$1"
active_id="$2"
paused_host=$(docker exec "$paused_id" sh -c 'echo $HOSTNAME')
paused_queue="chat.live.$paused_host"
ip_paused=$(ip_of "$paused_id")
ip_active=$(ip_of "$active_id")
room=$(new_room)
start_client "s6-$RUN_ID-active" "$ip_active" "$room"
docker pause "$paused_id" >/dev/null
for part in 1 2 3 4 5; do
  send_messages "$ip_active" "$room" "s6-$RUN_ID-$part" 1000 "S6-$RUN_ID-$part-" &
done
max_depth=0
while [ -n "$(jobs -r)" ]; do
  depth=$(list_queues | awk -v q="$paused_queue" '$1 == q { print $2 }')
  if [ "${depth:-0}" -gt "$max_depth" ]; then
    max_depth="$depth"
  fi
  sleep 1
done
wait
depth=$(list_queues | awk -v q="$paused_queue" '$1 == q { print $2 }')
if [ "${depth:-0}" -gt "$max_depth" ]; then
  max_depth="$depth"
fi
wait_for_events "s6-$RUN_ID-active" 5000 120
active_got=$(events_of "s6-$RUN_ID-active")
docker unpause "$paused_id" >/dev/null
sleep 3
resumed_room=$(new_room)
start_client "s6-$RUN_ID-resumed" "$ip_paused" "$resumed_room"
send_messages "$ip_active" "$resumed_room" "s6r-$RUN_ID" 10 "S6R-$RUN_ID-"
wait_for_events "s6-$RUN_ID-resumed" 10 15
resumed_got=$(events_of "s6-$RUN_ID-resumed")
if [ "$max_depth" -le 1000 ] && [ "$active_got" = "5000" ] && [ "$resumed_got" = "10" ]; then
  report S6 yes "Queue der pausierten Instanz höchstens $max_depth; aktive Instanz 5000; nach Fortsetzen 10 von 10 live"
else
  report S6 no "Queue der pausierten Instanz bis $max_depth; aktive Instanz $active_got; nach Fortsetzen $resumed_got von 10"
fi
remove_clients

# Zusatz zu S6: Die Pause dauert länger als der RabbitMQ-Herzschlag (60 s, zwei verpasste).
# RabbitMQ trennt dann die Verbindung und löscht die exklusive Queue. Nach dem Fortsetzen
# muss die Instanz sie neu anlegen und wieder zustellen.
echo "S6 (lange Pause): 150 s pausieren, danach muss die Instanz wieder zustellen"
docker pause "$paused_id" >/dev/null
sleep 150
queue_gone=$(list_queues | grep -c "^$paused_queue")
docker unpause "$paused_id" >/dev/null
recovered=no
if wait_for_live_queues 2 60; then
  recovered=yes
fi
long_room=$(new_room)
start_client "s6-$RUN_ID-long" "$ip_paused" "$long_room"
send_messages "$ip_active" "$long_room" "s6l-$RUN_ID" 10 "S6L-$RUN_ID-"
wait_for_events "s6-$RUN_ID-long" 10 15
long_got=$(events_of "s6-$RUN_ID-long")
if [ "$recovered" = "yes" ] && [ "$long_got" = "10" ]; then
  report S6 yes "lange Pause: Queue während der Pause $( [ "$queue_gone" = "0" ] && echo gelöscht || echo noch da ), danach neu angelegt, 10 von 10 live"
else
  report S6 no "lange Pause: Queue wieder da: $recovered, $long_got von 10 live"
fi
remove_clients

# ---------- S7: trennen, 50 verpassen, mit Last-Event-ID neu verbinden ----------
echo "S7: Client trennt, 50 Nachrichten, Neuverbindung mit Last-Event-ID"
ip=$(ip_of "$1")
room=$(new_room)
prefix="S7-$RUN_ID-"
start_client "s7-$RUN_ID-first" "$ip" "$room"
send_messages "$ip" "$room" "s7-$RUN_ID" 1 "S7-$RUN_ID-vorher-"
wait_for_events "s7-$RUN_ID-first" 1 10
last_id=$(ids_of "s7-$RUN_ID-first" | tail -1)
remove_clients
send_messages "$ip" "$room" "s7-$RUN_ID" 50 "$prefix"
wait_for_prefix "$prefix" 50 60 >/dev/null
expected_ids=$(sql "SELECT string_agg(CAST(id AS TEXT), ',' ORDER BY sent_at, id) FROM message WHERE room_id = '$room' AND (sent_at, id) > (SELECT sent_at, id FROM message WHERE id = '$last_id')")
start_client "s7-$RUN_ID-resumed" "$ip" "$room" "$last_id"
wait_for_events "s7-$RUN_ID-resumed" 50 15
sleep 2
caught_up=$(events_of "s7-$RUN_ID-resumed")
received_ids=$(ids_of "s7-$RUN_ID-resumed" | paste -sd, -)
send_messages "$ip" "$room" "s7-$RUN_ID" 1 "S7-$RUN_ID-danach-"
wait_for_events "s7-$RUN_ID-resumed" 51 10
after_live=$(events_of "s7-$RUN_ID-resumed")
if [ "$caught_up" = "50" ] && [ "$received_ids" = "$expected_ids" ] && [ "$after_live" = "51" ]; then
  report S7 yes "genau 50 nachgeholt, in (sent_at, id)-Reihenfolge, keine doppelt; danach wieder live"
else
  report S7 no "$caught_up nachgeholt, Reihenfolge gleich: $( [ "$received_ids" = "$expected_ids" ] && echo ja || echo nein ), nach der Live-Nachricht $after_live"
fi
remove_clients

# ---------- S8: Codestil im chat-service, .env nicht im Repo ----------
echo "S8: Quelltext prüfen"
streams=$(grep -rnE '\.stream\(\)|Stream\.of|Collectors|\.forEach\(|IntStream' chat-service/src | wc -l | tr -d ' ')
env_tracked=$(git ls-files .env | wc -l | tr -d ' ')
# Über jeder Klasse und Methode muss ein Kommentar stehen. Annotationen
# dazwischen, auch mehrzeilige, werden übersprungen.
missing=$(find chat-service/src -name '*.java' -print0 | xargs -0 awk '
  FNR == 1 { last = ""; depth = 0 }
  {
    line = $0
    sub(/^[ \t]+/, "", line)
    if (depth > 0) { depth += gsub(/\(/, "(", line) - gsub(/\)/, ")", line); next }
    if (line == "") next
    if (line ~ /^@/) { depth = gsub(/\(/, "(", line) - gsub(/\)/, ")", line); next }
    is_type = line ~ /^((public|protected|private|static|final|abstract) )*(class|record|interface|enum) /
    is_ctor = line ~ /^(public|protected|private) [A-Z][A-Za-z0-9]*\(/
    is_method = line ~ /^((public|protected|private|static|final|synchronized) )*[A-Za-z][A-Za-z0-9_<>?,\[\] ]* [a-z][A-Za-z0-9_]*\(/ && line !~ /;[ \t]*$/ && line !~ /^(return|new|throw|if|for|while|switch|catch|try|else|synchronized) /
    if (is_type || is_ctor || is_method) {
      if (last !~ /\*\/$/ && last !~ /^\/\//) print FILENAME ":" FNR ": " line
    }
    last = line
  }' | wc -l | tr -d ' ')
if [ "$streams" = "0" ] && [ "$env_tracked" = "0" ] && [ "$missing" = "0" ]; then
  report S8 yes "keine Streams, .env nicht im Repo, jede Klasse und Methode kommentiert"
else
  report S8 no "$streams Stream-Stellen, .env im Repo: $env_tracked, $missing Deklarationen ohne Kommentar"
fi

echo
if [ "$FAILED" = "0" ]; then
  echo "Alle Szenarien S2 bis S8 der Live-Zustellung bestanden."
else
  echo "Mindestens ein Szenario ist nicht bestanden."
fi
exit "$FAILED"
