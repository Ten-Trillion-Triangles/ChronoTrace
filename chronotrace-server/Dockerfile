# Build with JDK 21 to avoid Kotlin 2.2.21 + JDK 25 crash (JavaVersion.parse).
# The eclipse-temurin:21-jdk image provides the exact JDK version gradle.properties expects.
FROM eclipse-temurin:21-jdk

WORKDIR /app

# Copy full project (gradlew, gradle/, gradle.properties are at repo root)
COPY . /app

RUN chmod +x ./gradlew && \
    sed -i "s|org\.gradle\.java\.home=.*|org.gradle.java.home=/opt/java/openjdk|" gradle.properties && \
    ./gradlew :chronotrace-server:installDist \
    --no-configuration-cache

CMD ["./chronotrace-server/build/install/chronotrace-server/bin/chronotrace-server"]
