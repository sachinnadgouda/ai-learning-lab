FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q verify

FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache curl && addgroup -S rag && adduser -S rag -G rag
WORKDIR /app
COPY --from=build /build/target/local-rag-0.1.0.jar app.jar
USER rag
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
