package dev.zephyr.core.settings;

public class BooleanSetting extends Setting<Boolean> {

    public BooleanSetting(String id, String label, boolean defaultValue) {
        super(id, label, "", defaultValue);
    }

    public BooleanSetting(String id, String label, String description, boolean defaultValue) {
        super(id, label, description, defaultValue);
    }

    @Override
    protected Boolean validate(Boolean candidate) {
        return candidate != null ? candidate : null;
    }
}
