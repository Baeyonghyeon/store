FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew bootJar --no-daemon
COPY docker/Healthcheck.java /build/Healthcheck.java
RUN javac /build/Healthcheck.java

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --create-home app && mkdir -p /app/artifacts /app/locks && chown -R app:app /app
COPY --from=build /build/build/libs/bdd-llm-0.1.0.jar /app/app.jar
COPY --from=build /build/Healthcheck.class /app/Healthcheck.class
COPY config /app/config
COPY evidence /app/evidence
COPY samples /app/samples
USER app
EXPOSE 8000 8001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
