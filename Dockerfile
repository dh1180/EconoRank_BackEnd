FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system statagora && useradd --system --gid statagora statagora
COPY --from=build --chown=statagora:statagora /app/target/statagora-0.1.0.jar app.jar
USER statagora
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
