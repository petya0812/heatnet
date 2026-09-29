FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY pom.xml .
# dependency layer cached separately from the sources (go-offline ignores exclusions of the map webjar)
RUN mvn -q -B dependency:resolve dependency:resolve-plugins
COPY src src
RUN mvn -q -B -DskipTests package

# Ubuntu 22.04 based JRE 11 (ТЗ, раздел 3)
FROM eclipse-temurin:11-jre-jammy
WORKDIR /app
COPY --from=build /build/target/heatnet.jar app.jar
RUN mkdir -p /data/uploads /data/tmp /data/results
ENV JAVA_OPTS="-Xmx4g" \
    HEATNET_STORAGE_DIR=/data
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
