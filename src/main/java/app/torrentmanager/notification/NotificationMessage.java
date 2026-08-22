package app.torrentmanager.notification;

public record NotificationMessage(String subject, String body) {
    public NotificationMessage {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Тема уведомления не должна быть пустой");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Текст уведомления не должен быть пустым");
        }
    }
}
