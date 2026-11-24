# Telegram ownership during integration

Set `TORRENT_MANAGER_TELEGRAM_MODE=INTEGRATED` when the Assistant owns the bot.
The Torrent Manager command poller is then absent; direct Telegram sends are
disabled. The backend can start without a Telegram token. The default
`AUTONOMOUS` keeps the existing Telegram interface for separate installations.

Before the switch, back up the deployed JAR, configuration and state. Check
the speed timer and any pending speed transition. Preserve the notification
outbox, Telegram offset and all existing bind mounts. In Portainer, update
only Torrent Manager, retain its host network and validate its health and
speed snapshot before bringing up the Assistant poller.

The integration read bearer key is required for
`GET /api/integration/v1/notifications`. The write key is required for
`POST /api/integration/v1/notifications/{id}/ack`. Acknowledgement is
idempotent. Keep a single Assistant consumer for this outbox. The Assistant
database advisory lock excludes a second process targeting the same database;
do not point installations with different databases at the same outbox.

For rollback, stop Assistant notification delivery and polling before switching
Torrent Manager back to autonomous mode if both installations used the same
bot. Keep live state. Inspect pending deliveries and Assistant receipts before
restoring direct delivery so an acknowledgement lost at cutover does not
produce an unexpected duplicate. Never restore the old queue, timer or offset
over newer state automatically.
