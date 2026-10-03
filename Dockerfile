FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q package -DskipTests
FROM eclipse-temurin:17-jre
COPY --from=build /app/target/*.jar /app.jar
CMD ["sh","-c","java -Xmx300m -Dserver.port=${PORT:-8080} -jar /app.jar"]
