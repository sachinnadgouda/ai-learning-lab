FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q verify

FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache curl && addgroup -S rag && adduser -S rag -G rag
WORKDIR /app
RUN mkdir data && chown rag:rag data
COPY --from=build /build/target/local-rag.jar app.jar
USER rag
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
