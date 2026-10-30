FROM maven:3.9.16-eclipse-temurin-21 AS builder
WORKDIR /app
COPY auth-service/pom.xml .
RUN mvn dependency:go-offline -B -ntp
COPY auth-service/src ./src
RUN mvn -B -ntp clean package -DskipTests
COPY docker/ReadinessCheck.java /probe/ReadinessCheck.java
RUN javac --release 21 -d /probe /probe/ReadinessCheck.java

FROM eclipse-temurin:21-jre AS runner
WORKDIR /app
COPY --from=builder /app/target/auth-service-0.0.1-SNAPSHOT.jar ./app.jar
COPY --from=builder /probe/ReadinessCheck.class /probe/ReadinessCheck.class
COPY docker/certs/global-bundle.pem /app/certs/global-bundle.pem
EXPOSE 4005
ENTRYPOINT ["java", "-jar", "app.jar"]
