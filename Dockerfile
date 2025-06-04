# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-alpine AS build

# Install Maven
RUN apk add --no-cache maven

WORKDIR /app

# Copy build config first for cache
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source
COPY src ./src

# Optional: enforce UTF-8 encoding of YAML/properties
RUN find src/main/resources -name "*.yml" -exec sed -i 's/\r//' {} \;

# Build JAR
RUN mvn clean package -DskipTests -Dfile.encoding=UTF-8

# Runtime
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Install curl for health checks
RUN apk add --no-cache curl

# Create non-root user
RUN addgroup -S studychat && adduser -S studychat -G studychat

# Copy JAR
COPY --from=build /app/target/studyChat-0.0.1-SNAPSHOT.jar app.jar

# Prepare logs directory
RUN mkdir -p /app/logs && chown -R studychat:studychat /app

USER studychat

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
