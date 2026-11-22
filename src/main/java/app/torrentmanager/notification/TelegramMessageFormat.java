package app.torrentmanager.notification;

public final class TelegramMessageFormat {
    private TelegramMessageFormat() { }

    public static String progress(double fraction) {
        int percent = (int) Math.round(Math.max(0, Math.min(1, fraction)) * 100);
        int filled = percent / 10;
        return "▓".repeat(filled) + "░".repeat(10 - filled) + " " + percent + "%";
    }
}
