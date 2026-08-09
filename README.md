# Torrent Manager

Torrent Manager is a small Spring Boot service that monitors qBittorrent downloads and pauses slow torrents when the configured active-download limit is reached. It keeps a local state file so torrents paused by the service can be resumed later.

## Project status

Version `0.5.0` is a usable pre-1.0 release. The core management loop, qBittorrent Web API integration, persistent state, Docker packaging, and unit tests are present. Configuration and behavior may still change before `1.0.0`.

The service starts in dry-run mode by default. Review the logs and configuration before allowing it to stop or resume torrents.

## Requirements

- Java 25, or Docker
- qBittorrent with the Web UI enabled

## Configuration

Copy `.env.example` to `.env` and replace all placeholder values:

```dotenv
QBITTORRENT_URL=http://127.0.0.1:8080
QBITTORRENT_USERNAME=replace-me
QBITTORRENT_PASSWORD=replace-me
APP_DIR=/absolute/path/to/torrent-manager
TORRENT_MANAGER_DRY_RUN=true
TZ=UTC
```

`APP_DIR` is used only by `compose.yaml` and must contain `torrent-manager.jar`. The `.env` file, logs, runtime data, build outputs, and deployment archives are excluded from Git.

Additional tuning options are documented in `src/main/resources/application.properties` and can be overridden with standard Spring Boot configuration. Set `TZ` to an IANA time-zone name such as `Europe/Moscow` to display log times in that zone.

## Build and test

```shell
./gradlew test
./gradlew bootJar
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Run locally

Set the three `QBITTORRENT_*` environment variables, then run:

```shell
./gradlew bootRun
```

## Run with Docker Compose

Build the JAR, copy it to `APP_DIR`, review `.env`, and start the service:

```shell
./gradlew bootJar
docker compose up -d
```

Health information is available at `/actuator/health` on port `8091` when using the provided Compose configuration.

## Versioning

The project follows Semantic Versioning. Releases before `1.0.0` indicate a working but evolving service; minor releases add or change functionality, and patch releases contain compatible fixes.
