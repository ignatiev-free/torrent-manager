package app.torrentmanager.settings;

public record SettingField(String key, String label, String type, String section, String help) {
    static SettingField field(String key, String label, String type, String section, String help) {
        return new SettingField(key, label, type, section, help);
    }
}
