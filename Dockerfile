FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY . .
# Utilizamos as mesmas flags Wagon SSL bypass para garantir que o build funcione na sua rede
RUN mvn clean package -DskipTests -Dmaven.resolver.transport=wagon -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true -Dmaven.wagon.http.ssl.ignore.validity.dates=true

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/cortex-api/target/cortex-api-1.0-SNAPSHOT-exec.jar /app/cortex-api.jar
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/cortex-api.jar"]
