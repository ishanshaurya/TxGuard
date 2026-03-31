FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
RUN apk add --no-cache maven
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q
RUN java -Djarmode=layertools -jar target/*.jar extract --destination target/layers

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S bouncer && adduser -S bouncer -G bouncer
USER bouncer
COPY --from=builder /build/target/layers/dependencies/ ./
COPY --from=builder /build/target/layers/spring-boot-loader/ ./
COPY --from=builder /build/target/layers/snapshot-dependencies/ ./
COPY --from=builder /build/target/layers/application/ ./
EXPOSE 8080
ENTRYPOINT ["java","-XX:+UseContainerSupport","-XX:MaxRAMPercentage=75.0","-Djava.security.egd=file:/dev/./urandom","org.springframework.boot.loader.launch.JarLauncher"]
