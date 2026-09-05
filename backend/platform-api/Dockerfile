FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src src
RUN mvn -B verify package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 theapp
COPY --from=build /workspace/target/platform-api-*.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
