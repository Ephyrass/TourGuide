# Multi-stage Dockerfile: build with Maven then produce a minimal runtime image
# Stage 1: build the application using Maven
FROM maven:3.9.4-eclipse-temurin-17 AS build
WORKDIR /workspace

# Copy pom and local libs first to leverage Docker cache
COPY pom.xml ./
COPY libs ./libs

# Download dependencies offline
RUN mvn -B -DskipTests=true dependency:go-offline

# Copy the rest of the source and package
COPY . .
RUN mvn -B -DskipTests=true package

# Stage 2: runtime image
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Copy the packaged jar from the build stage; the wildcard selects the built jar
COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

