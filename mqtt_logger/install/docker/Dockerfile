FROM eclipse-temurin:21-jre
RUN groupadd --system maps-logger \
 && useradd --system --gid maps-logger --no-create-home --home-dir /nonexistent \
      --shell /usr/sbin/nologin maps-logger \
 && install -d -m 0750 -o maps-logger -g maps-logger /var/log/maps-logger
COPY mqtt_logger-*.jar /app/maps-value-logger.jar
USER maps-logger
ENTRYPOINT ["java", "-jar", "/app/maps-value-logger.jar"]
