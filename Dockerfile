# =========================================================================
# SSP-ARR Cloud Task Scheduling Simulator & Web API
# Production Multi-Stage Container Image for Render / Railway / Cloud Run
# =========================================================================

# Stage 1: Build Application
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Cache dependency layer
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy project source and data files
COPY src ./src
COPY data ./data
COPY dashboard ./dashboard
COPY results ./results

# Compile classes and package dependencies into target/lib
RUN mvn clean compile
RUN mvn dependency:copy-dependencies -DoutputDirectory=target/lib

# Stage 2: Lean JRE Runtime Image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy application binaries, libraries, and datasets
COPY --from=builder /app/target/classes ./target/classes
COPY --from=builder /app/target/lib ./target/lib
COPY --from=builder /app/data ./data
COPY --from=builder /app/dashboard ./dashboard
COPY --from=builder /app/results ./results

# Container Port Configuration
ENV PORT=8080
EXPOSE 8080

# Healthcheck for container orchestrators
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:${PORT}/api/health || exit 1

# Start the built-in JDK 21 Virtual-Threaded Simulator Server
CMD ["java", "-cp", "target/classes:target/lib/*", "com.cloudscheduling.simulation.SimulationServer"]
