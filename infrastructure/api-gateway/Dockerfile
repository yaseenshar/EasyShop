FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
# Copy all module pom.xml files so Maven can resolve the full reactor
COPY pom.xml .
COPY common/common-lib/pom.xml common/common-lib/pom.xml
COPY infrastructure/api-gateway/pom.xml infrastructure/api-gateway/pom.xml
COPY infrastructure/service-discovery/pom.xml infrastructure/service-discovery/pom.xml
# Copy only the sources needed for this module and its dependencies
COPY common/common-lib/src common/common-lib/src
COPY infrastructure/api-gateway/src infrastructure/api-gateway/src
RUN mvn package -pl infrastructure/api-gateway -am -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/infrastructure/api-gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
