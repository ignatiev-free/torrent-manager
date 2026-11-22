package app.torrentmanager.notification;

public interface NotificationSender {
    String channel();

    void send(NotificationMessage message);

    /** Returns true when delivery was queued. */
    default boolean submit(NotificationMessage message) {
        send(message);
        return false;
    }
}
