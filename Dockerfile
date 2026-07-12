FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache git openssh-client
WORKDIR /app

# Copiamos o JAR já compilado localmente na máquina host para contornar problemas de DNS/SSL no Docker
COPY cortex-api/target/cortex-api-1.0-SNAPSHOT-exec.jar /app/cortex-api.jar
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/cortex-api.jar"]
