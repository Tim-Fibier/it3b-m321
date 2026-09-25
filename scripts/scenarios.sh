#!/usr/bin/env bash
# Stellt die Szenarien S2 bis S8 aus der Bewertung nach (docs/spec-batch-writer.md, Abschnitt 5).
#
# Aufruf im Wurzelverzeichnis des Repos:   bash scripts/scenarios.sh
# Voraussetzung: Docker läuft und .env existiert (cp .env.example .env).
# S1 ist "mvn clean test" und läuft getrennt davon.
#
# Die Szenarien laufen wie bei der Lehrperson nacheinander auf DEMSELBEN
# Stack, ohne Aufräumen dazwischen. Damit ältere Zeilen nicht mitgezählt
# werden, bekommt jede Nachricht einen eindeutigen Text: "<Szenario>-<Lauf>-<i>".
# Gezählt wird dann genau dieser Text.

set -u

RUN_ID=$(date +%s)
FAILED=0
ROOM_ID="3f2b1c4e-0000-0000-0000-000000000001"

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

# Führt eine SQL-Abfrage in der Chat-Datenbank aus, von innen im postgres-Container.
sql() {
  docker compose exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -tAc \"$1\"" | tr -d '[:space:]'
}

# Anzahl Zeilen, deren Text mit dem gegebenen Präfix beginnt.
count_prefix() {
  sql "SELECT count(*) FROM message WHERE content LIKE '$1%'"
}

# Wie viele Nachrichten in einer Queue bereitliegen.
queue_depth() {
  docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages | awk -v q="$1" '$1 == q { print $2 }'
}

# Wie viele Verbraucher an einer Queue hängen.
queue_consumers() {
  docker compose exec -T rabbitmq rabbitmqctl -q list_queues name consumers | awk -v q="$1" '$1 == q { print $2 }'
}

# Committete Transaktionen der Chat-Datenbank seit ihrem Start.
committed_transactions() {
  sql "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()"
}

# Schickt N Nachrichten über POST /messages, von innen aus dem Netz chat-net.
send_messages() {
  local count="$1" prefix="$2"
  docker run --rm --network chat-net curlimages/curl sh -c "
    for i in \$(seq 1 $count); do
      curl -s -o /dev/null -X POST http://chat-service:8080/messages \
        -H 'Content-Type: application/json' \
        -d \"{\\\"roomId\\\":\\\"$ROOM_ID\\\",\\\"senderId\\\":\\\"anna\\\",\\\"senderName\\\":\\\"Anna Muster\\\",\\\"content\\\":\\\"$prefix\$i\\\"}\"
    done"
}

# Wartet höchstens $3 Sekunden, bis mindestens $2 Zeilen mit dem Präfix $1 da sind.
wait_for_prefix() {
  local prefix="$1" expected="$2" timeout="$3" waited=0 rows
  rows=$(count_prefix "$prefix")
  while [ "${rows:-0}" -lt "$expected" ] && [ "$waited" -lt "$timeout" ]; do
    sleep 2
    waited=$((waited + 2))
    rows=$(count_prefix "$prefix" 2>/dev/null)
  done
  echo "${rows:-0}"
}

# Wartet höchstens $3 Sekunden, bis die Queue $1 genau $2 Nachrichten hat.
wait_for_queue_depth() {
  local queue="$1" expected="$2" timeout="$3" waited=0
  while [ "$(queue_depth "$queue")" != "$expected" ] && [ "$waited" -lt "$timeout" ]; do
    sleep 1
    waited=$((waited + 1))
  done
}

# Start und Neustarts aller batch-writer-Container, um S7 zu prüfen.
batch_writer_starts() {
  local ids
  ids=$(docker compose ps -q batch-writer)
  docker inspect -f '{{.State.StartedAt}}/{{.RestartCount}}' $ids | sort | tr '\n' ' '
}

# ---------- S2: Stack läuft, kein Port veröffentlicht ----------
echo "S2: Stack starten"
docker compose up -d --build >/dev/null 2>&1
waited=0
while [ "$(queue_consumers chat.persist)" != "1" ] && [ "$waited" -lt 180 ]; do
  sleep 3
  waited=$((waited + 3))
done
running=$(docker compose ps --format '{{.Service}} {{.State}}' | grep -c ' running')
services=$(docker compose config --services | wc -l | tr -d ' ')
published=$(docker compose ps --format '{{.Ports}}' | grep -c -- '->')
if [ "$running" = "$services" ] && [ "$published" = "0" ]; then
  report S2 yes "$running von $services Diensten laufen, 0 veröffentlichte Ports"
else
  report S2 no "$running von $services Diensten laufen, $published Dienste mit veröffentlichtem Port"
fi

# ---------- S3: 1000 Nachrichten in höchstens 60 s ----------
echo "S3: 1000 Nachrichten senden"
prefix="S3-$RUN_ID-"
send_messages 1000 "$prefix"
rows=$(wait_for_prefix "$prefix" 1000 60)
depth=$(queue_depth chat.persist)
if [ "$rows" = "1000" ] && [ "$depth" = "0" ]; then
  report S3 yes "1000 Zeilen, chat.persist leer"
else
  report S3 no "$rows Zeilen, chat.persist $depth"
fi

# ---------- S4: gestoppt, 1000 gesendet, höchstens 100 Transaktionen ----------
echo "S4: batch-writer stoppen, 1000 senden, starten"
prefix="S4-$RUN_ID-"
docker compose stop batch-writer >/dev/null 2>&1
send_messages 1000 "$prefix"
wait_for_queue_depth chat.persist 1000 30
before=$(committed_transactions)
docker compose start batch-writer >/dev/null 2>&1
rows=$(wait_for_prefix "$prefix" 1000 90)
wait_for_queue_depth chat.persist 0 30
sleep 2
after=$(committed_transactions)
used=$((after - before))
if [ "$rows" = "1000" ] && [ "$used" -le 100 ]; then
  report S4 yes "1000 Zeilen, $used Transaktionen"
else
  report S4 no "$rows Zeilen, $used Transaktionen"
fi

# ---------- S5: dieselbe Nachricht zweimal, nur mit content_type ----------
echo "S5: Duplikat direkt in chat.persist legen"
dlq_before=$(queue_depth chat.dlq)
message_id=$(sql "SELECT gen_random_uuid()")
payload="{\"id\":\"$message_id\",\"roomId\":\"$ROOM_ID\",\"senderId\":\"anna\",\"senderName\":\"Anna Muster\",\"content\":\"S5-$RUN_ID\",\"sentAt\":\"2026-09-25T08:28:43.509872789Z\"}"
for attempt in 1 2; do
  docker compose exec -T -e PAYLOAD="$payload" rabbitmq sh -c \
    'rabbitmqadmin -u "$RABBITMQ_DEFAULT_USER" -p "$RABBITMQ_DEFAULT_PASS" publish exchange=amq.default routing_key=chat.persist properties="{\"content_type\":\"application/json\"}" payload="$PAYLOAD"' >/dev/null
done
sleep 5
rows=$(sql "SELECT count(*) FROM message WHERE id = '$message_id'")
dlq_after=$(queue_depth chat.dlq)
if [ "$rows" = "1" ] && [ "$dlq_after" = "$dlq_before" ]; then
  report S5 yes "1 Zeile, chat.dlq unverändert ($dlq_after)"
else
  report S5 no "$rows Zeilen, chat.dlq $dlq_before -> $dlq_after"
fi

# ---------- S6: zwei Instanzen ----------
echo "S6: auf zwei batch-writer skalieren, 1000 senden"
prefix="S6-$RUN_ID-"
docker compose up -d --scale batch-writer=2 >/dev/null 2>&1
waited=0
while [ "$(queue_consumers chat.persist)" != "2" ] && [ "$waited" -lt 120 ]; do
  sleep 3
  waited=$((waited + 3))
done
consumers=$(queue_consumers chat.persist)
send_messages 1000 "$prefix"
rows=$(wait_for_prefix "$prefix" 1000 60)
distinct=$(sql "SELECT count(DISTINCT content) FROM message WHERE content LIKE '$prefix%'")
if [ "$consumers" = "2" ] && [ "$rows" = "1000" ] && [ "$distinct" = "1000" ]; then
  report S6 yes "2 Verbraucher, 1000 Zeilen, keine doppelt"
else
  report S6 no "$consumers Verbraucher, $rows Zeilen, $distinct verschiedene"
fi

# ---------- S7: Postgres 15 s weg ----------
echo "S7: postgres stoppen, 300 senden, nach 15 s wieder starten"
prefix="S7-$RUN_ID-"
dlq_before=$(queue_depth chat.dlq)
starts_before=$(batch_writer_starts)
stopped_at=$(date +%s)
docker compose stop postgres >/dev/null 2>&1
send_messages 300 "$prefix"
remaining=$((15 - ($(date +%s) - stopped_at)))
if [ "$remaining" -gt 0 ]; then
  sleep "$remaining"
fi
docker compose start postgres >/dev/null 2>&1
rows=$(wait_for_prefix "$prefix" 300 90)
dlq_after=$(queue_depth chat.dlq)
starts_after=$(batch_writer_starts)
if [ "$rows" = "300" ] && [ "$dlq_after" = "$dlq_before" ] && [ "$starts_after" = "$starts_before" ]; then
  report S7 yes "300 Zeilen, chat.dlq unverändert, kein Neustart des batch-writer"
else
  report S7 no "$rows Zeilen, chat.dlq $dlq_before -> $dlq_after, Starts vorher [$starts_before] nachher [$starts_after]"
fi

# ---------- S8: Codestil ----------
echo "S8: Quelltext prüfen"
streams=$(grep -rnE '\.stream\(\)|Stream\.of|Collectors|\.forEach\(|IntStream' batch-writer/src | wc -l | tr -d ' ')
env_tracked=$(git ls-files .env | wc -l | tr -d ' ')
# Über jeder Klasse und Methode muss ein Kommentar stehen. Annotationen
# dazwischen, auch mehrzeilige, werden übersprungen.
missing=$(find batch-writer/src -name '*.java' -print0 | xargs -0 awk '
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
  echo "Alle Szenarien S2 bis S8 bestanden."
else
  echo "Mindestens ein Szenario ist nicht bestanden."
fi
exit "$FAILED"
