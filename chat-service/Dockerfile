# Stufe 1: Maven Build
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy pom.xml
COPY pom.xml .

# Download dependencies (für schnelleres Rebuild mit Cache)
RUN mvn dependency:go-offline -B

# Copy Source Code
COPY src ./src

# Baue JAR
RUN mvn clean package -DskipTests -B

# Stufe 2: Laufzeit
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy JAR von builder
COPY --from=builder /build/target/*.jar app.jar

# Health Check
HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD wget --quiet --tries=1 --spider http://localhost:8081/actuator/health || exit 1

# Start Application
ENTRYPOINT ["java", "-jar", "app.jar"]
