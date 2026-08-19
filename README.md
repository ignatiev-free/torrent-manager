# Torrent Manager

Torrent Manager — небольшой сервис на Spring Boot, который следит за загрузками в qBittorrent и приостанавливает медленные торренты, когда достигнут заданный лимит активных загрузок. Сервис хранит состояние в локальном файле, чтобы позднее возобновлять торренты, приостановленные им самим.

## Статус проекта

Версия `0.5.1` — работоспособный выпуск до версии 1.0. Основной цикл управления, интеграция с qBittorrent Web API, сохранение состояния, упаковка в Docker и модульные тесты уже реализованы. До выпуска `1.0.0` конфигурация и поведение сервиса могут изменяться.

По умолчанию сервис запускается в режиме имитации (`dry-run`). Проверьте журналы и конфигурацию, прежде чем разрешать ему останавливать или возобновлять торренты.

## Требования

- Java 25 или Docker
- qBittorrent с включённым веб-интерфейсом

## Настройка

Скопируйте `.env.example` в `.env` и замените все значения-заглушки:

```dotenv
QBITTORRENT_URL=http://127.0.0.1:8080
QBITTORRENT_USERNAME=replace-me
QBITTORRENT_PASSWORD=replace-me
APP_DIR=/absolute/path/to/torrent-manager
TORRENT_MANAGER_DRY_RUN=true
TORRENT_MANAGER_RETRY_COOLDOWN=20m
TRAFFIC_STATS_POLL_INTERVAL=1m
TZ=UTC
```

Переменная `APP_DIR` используется только в `compose.yaml` и должна указывать на каталог, содержащий `torrent-manager.jar`. Файл `.env`, журналы, рабочие данные, результаты сборки и архивы для развёртывания исключены из Git.

Дополнительные параметры описаны в `src/main/resources/application.properties`. Их можно переопределять стандартными средствами конфигурации Spring Boot.

`TORRENT_MANAGER_RETRY_COOLDOWN` задаёт минимальное время ожидания перед повторным запуском торрента, остановленного менеджером. По умолчанию используется `20m`; фактическое ожидание может быть дольше, пока нет свободного слота или в qBittorrent остаются загрузки в очереди. Поддерживаются форматы длительности Spring, например `30s`, `20m` и `2h`.

`TZ` задаёт часовую зону для времени в журнале. Используйте имя из базы IANA, например `Europe/Moscow`; по умолчанию применяется `UTC`.

## Статистика трафика qBittorrent

Сервис раз в минуту считывает накопительные счётчики qBittorrent и сохраняет суточное состояние в `data/traffic-state.properties`. Благодаря этому значения за текущий календарный день переживают перезапуск контейнера. Граница суток определяется переменной `TZ`.

Текущая статистика доступна по адресу:

```text
GET /api/qbittorrent/traffic
```

Ответ содержит готовые для отображения строки и исходные значения в байтах:

```json
{
  "date": "2026-08-19",
  "downloadToday": "84.2 GiB",
  "uploadToday": "6.8 GiB",
  "downloadAllTime": "12.4 TiB",
  "uploadAllTime": "1.1 TiB",
  "downloadTodayBytes": 90409091072,
  "uploadTodayBytes": 7301444403,
  "downloadAllTimeBytes": 13633944184422,
  "uploadAllTimeBytes": 1209462790553,
  "trackingSince": "2026-08-19T06:00:00Z"
}
```

В первый день суточные значения считаются с момента первого успешного опроса. После следующей полуночи они соответствуют календарным суткам. Интервал можно изменить через `TRAFFIC_STATS_POLL_INTERVAL`.

Пример отдельной карточки [Homepage](https://gethomepage.dev/widgets/services/customapi/):

```yaml
- Трафик qBittorrent:
    icon: qbittorrent.png
    description: Статистика передачи данных
    widget:
      type: customapi
      url: http://nas-host:8091/api/qbittorrent/traffic
      refreshInterval: 60000
      mappings:
        - field: downloadToday
          label: Скачано сегодня
        - field: uploadToday
          label: Отдано сегодня
        - field: downloadAllTime
          label: Скачано всего
        - field: uploadAllTime
          label: Отдано всего
```

## Сборка и тестирование

```shell
./gradlew test
./gradlew bootJar
```

В Windows используйте `gradlew.bat` вместо `./gradlew`.

## Локальный запуск

Задайте три переменные окружения `QBITTORRENT_*`, затем выполните:

```shell
./gradlew bootRun
```

## Запуск с Docker Compose

Соберите JAR-файл, скопируйте его в `APP_DIR`, проверьте `.env` и запустите сервис:

```shell
./gradlew bootJar
docker compose up -d
```

При использовании предоставленной конфигурации Compose информация о состоянии сервиса доступна по адресу `/actuator/health` на порту `8091`.

## Версионирование

Проект следует правилам семантического версионирования. Выпуски до `1.0.0` обозначают работоспособный, но развивающийся сервис: минорные версии добавляют или изменяют функциональность, а патч-версии содержат совместимые исправления.
