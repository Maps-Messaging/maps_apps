FROM eclipse-temurin:21-jre
COPY mqtt_logger-*.jar /app/maps-value-logger.jar
RUN mkdir -p /var/log/maps-logger
ENTRYPOINT ["java", "-jar", "/app/maps-value-logger.jar"]
