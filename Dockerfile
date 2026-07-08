# --- build stage ---
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# layer 1: gradle wrapper + build config (rarely changes → cached)
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# layer 2: source code (changes every commit → rebuilds only this layer)
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# --- runtime stage ---
FROM eclipse-temurin:17-jre
WORKDIR /app

# Fixed numeric UID so the Deployment can pin runAsUser: 10001 and the kubelet
# can verify runAsNonRoot at admission (a --system username has an unpredictable
# UID the restricted Pod Security Standard cannot check).
RUN groupadd --system --gid 10001 appgroup \
    && useradd --system --uid 10001 --gid appgroup appuser
USER 10001

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
