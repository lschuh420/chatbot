FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy pom files
COPY pom-docker.xml pom.xml

# Download dependencies
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Copy the Docker-specific YAML config
COPY application-docker.yml src/main/resources/

# Build the application
RUN mvn clean package -DskipTests -Pdocker

# Production stage
FROM eclipse-temurin:17-jre

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Create directories
RUN mkdir -p /app/collected-content /app/logs

# Copy the jar file from builder stage
COPY --from=builder /app/target/*.jar app.jar

# Create a non-root user
RUN addgroup --system spring && adduser --system spring --ingroup spring
RUN chown -R spring:spring /app
USER spring

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Environment variables
ENV JAVA_OPTS="-Xmx2g -Xms1g"
ENV SPRING_PROFILES_ACTIVE=docker

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]