# Build stage
FROM gradle:8.8-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle clean build -x test

# Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
