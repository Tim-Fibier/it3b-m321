# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht. Maven liest beim Start ALLE Module aus dem
# Eltern-POM ein, deshalb müssen auch die POMs der anderen Module da sein.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY chat-service/pom.xml chat-service/pom.xml
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY batch-writer/src batch-writer/src
# Tests werden hier übersprungen: Testcontainers bräuchte einen Docker-Daemon
# INNERHALB des Builds. Getestet wird vorher mit "mvn test".
RUN mvn -q -pl batch-writer -am package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/batch-writer/target/batch-writer-0.1.0-SNAPSHOT.jar app.jar
# Kein EXPOSE: der batch-writer hat keinen Webserver und keinen Port.
# Er spricht nur selbst nach aussen, mit RabbitMQ und PostgreSQL.
ENTRYPOINT ["java", "-jar", "app.jar"]
