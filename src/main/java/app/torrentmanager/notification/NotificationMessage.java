package app.torrentmanager.notification;

public record NotificationMessage(String subject, String body, String htmlBody, String telegramBody, String telegramTitle) {
    public NotificationMessage(String subject, String body, String htmlBody, String telegramBody) {
        this(subject, body, htmlBody, telegramBody, null);
    }

    public NotificationMessage(String subject, String body, String htmlBody) {
        this(subject, body, htmlBody, null);
    }

    public String telegramText() {
        return (telegramTitle == null ? subject : telegramTitle) + "\n\n" + (telegramBody == null ? body : telegramBody);
    }

    public NotificationMessage(String subject, String body) {
        this(subject, body, null);
    }

    public NotificationMessage {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Тема уведомления не должна быть пустой");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Текст уведомления не должен быть пустым");
        }
        if (htmlBody != null && htmlBody.isBlank()) {
            htmlBody = null;
        }
    }
}
