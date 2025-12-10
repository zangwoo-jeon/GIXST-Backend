# Use JDK 17
FROM openjdk:17-jdk-slim

# Set working directory
WORKDIR /app

# Copy build artifact
# Note: Adjust the path if the jar name is different after build
COPY build/libs/*.jar app.jar

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
