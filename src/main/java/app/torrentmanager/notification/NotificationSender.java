package app.torrentmanager.notification;

public interface NotificationSender {
    String channel();

    void send(NotificationMessage message);
}
