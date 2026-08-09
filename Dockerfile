FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle build.gradle gradle.properties ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app

RUN mkdir -p /app/logs /app/data
COPY --from=build /workspace/build/libs/torrent-manager-*.jar /app/torrent-manager.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/torrent-manager.jar"]
