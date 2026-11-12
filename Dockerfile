FROM eclipse-temurin:17-jre
WORKDIR /app
COPY sync-server/build/install/sync-server/ /app/
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/app/bin/sync-server", "8080", "/data/sync.bin"]
