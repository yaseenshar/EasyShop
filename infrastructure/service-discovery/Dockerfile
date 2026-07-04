FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
# Copy all module pom.xml files so Maven can resolve the full reactor
COPY pom.xml .
COPY common/common-lib/pom.xml common/common-lib/pom.xml
COPY infrastructure/service-discovery/pom.xml infrastructure/service-discovery/pom.xml
COPY infrastructure/api-gateway/pom.xml infrastructure/api-gateway/pom.xml
COPY services/user-service/pom.xml services/user-service/pom.xml
COPY services/order-service/pom.xml services/order-service/pom.xml
COPY services/payment-service/pom.xml services/payment-service/pom.xml
COPY services/inventory-service/pom.xml services/inventory-service/pom.xml
# Copy only the sources needed for this module and its dependencies
COPY common/common-lib/src common/common-lib/src
COPY infrastructure/service-discovery/src infrastructure/service-discovery/src
RUN mvn package -pl infrastructure/service-discovery -am -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/infrastructure/service-discovery/target/*.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
