# Builds Brief! (Spring Boot edition) into a small image that runs anywhere Docker does.
# Render builds this file by itself on every push; see render.yaml.
#
#   docker build -t brief .                 run from this folder (the one that holds backend-spring/)
#   docker run --rm -p 8080:8080 brief      then open http://localhost:8080
#
# Run like that, with no database configured, the container uses an H2 file inside itself and
# forgets everything when it stops. That is enough to see that the image works. A deployment
# gets DATABASE_URL from the platform and uses PostgreSQL.
#
# Two stages: the first has Maven and a full JDK and builds the jar; the second has only a Java
# runtime and receives the finished jar. The tools used for building do not end up in the image
# that runs.

# ---- 1. build the jar ----
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY backend-spring/pom.xml .
COPY backend-spring/src ./src
# The web app is already inside src/main/resources/public ("npm run build" in frontend-spring/ puts it there).
# Tests are skipped here: they run on your machine and in CI before anything is deployed.
RUN mvn -B -ntp -DskipTests package

# ---- 2. run it on a smaller image ----
FROM eclipse-temurin:17-jre
# Do not run as root. The user's home folder is /app, which it owns.
RUN useradd --system --create-home --home-dir /app brief
WORKDIR /app
USER brief
COPY --from=build --chown=brief /build/target/brief-spring.jar app.jar
# Documentation only. The application listens on $PORT if the platform sets it (Render does), else on 8080.
EXPOSE 8080
# By default Java uses a quarter of the container's memory for its heap. On a 512 MB instance
# that is too little; half leaves room for everything else Java needs outside the heap.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "app.jar"]
