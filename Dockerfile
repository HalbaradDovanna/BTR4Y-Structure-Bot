# ============================================================
# Dockerfile for EVE Structure Monitor Bot
# Railway will use this to build and run your application
# ============================================================

# Stage 1: Build the application
FROM maven:3.9.5-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy everything at once — simpler and avoids "src not found" errors
COPY . .

# Build the jar (skipping tests for faster builds)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Run the application (smaller final image)
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /app/target/structure-monitor-1.0.0.jar app.jar

# Railway sets PORT automatically; expose it
EXPOSE 8080

# Start the application
ENTRYPOINT ["java", "-jar", "app.jar"]
