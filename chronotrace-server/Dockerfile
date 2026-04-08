FROM eclipse-temurin:25-jre

WORKDIR /app
COPY . /app

RUN chmod +x ./gradlew && ./gradlew :chronotrace-server:installDist

CMD ["./chronotrace-server/build/install/chronotrace-server/bin/chronotrace-server"]
