FROM maven:3.9.16-eclipse-temurin-21 AS builder
WORKDIR /app
COPY api-gateway/pom.xml .
RUN mvn dependency:go-offline -B -ntp
COPY api-gateway/src ./src
RUN mvn -B -ntp clean package -DskipTests
COPY docker/ReadinessCheck.java /probe/ReadinessCheck.java
RUN javac --release 21 -d /probe /probe/ReadinessCheck.java

FROM eclipse-temurin:21-jre AS runner
WORKDIR /app
COPY --from=builder /app/target/api-gateway-0.0.1-SNAPSHOT.jar ./app.jar
COPY --from=builder /probe/ReadinessCheck.class /probe/ReadinessCheck.class
EXPOSE 4004
ENTRYPOINT ["java", "-jar", "app.jar"]
