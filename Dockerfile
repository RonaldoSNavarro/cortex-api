# syntax=docker/dockerfile:1
# check=error=true
FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends git openssh-client ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app

# Copiamos o JAR já compilado localmente na máquina host para contornar problemas de DNS/SSL no Docker
COPY --link cortex-api/target/cortex-api-1.0-SNAPSHOT-exec.jar /app/cortex-api.jar
EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseCompactObjectHeaders", "-jar", "/app/cortex-api.jar"]
