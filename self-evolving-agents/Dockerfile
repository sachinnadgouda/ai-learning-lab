FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY frontend/build.gradle ./frontend/build.gradle
COPY src ./src
RUN ./gradlew --no-daemon -x test :bootJar

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
