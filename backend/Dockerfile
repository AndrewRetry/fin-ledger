# --- build stage: compile with the Maven wrapper ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package

# --- runtime stage: small JRE image, non-root user ---
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1001 ledger
COPY --from=build /workspace/target/app.jar /app/app.jar
USER ledger

# cap heap at 75% to prevent server overload
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
