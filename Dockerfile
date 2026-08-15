FROM eclipse-temurin:25-jdk
RUN apt-get update && apt-get install -y git openssh-client && rm -rf /var/lib/apt/lists/*
WORKDIR /app

# Copiamos o JAR já compilado localmente na máquina host para contornar problemas de DNS/SSL no Docker
COPY cortex-api/target/cortex-api-1.0-SNAPSHOT-exec.jar /app/cortex-api.jar
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/cortex-api.jar"]
