package de.nes.core.model;

import java.util.regex.Pattern;

public record InstanceName(String value) {
    private static final Pattern VALID = Pattern.compile("[a-z0-9-]{1,63}");

    public InstanceName {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Name must be set, or is outside of guidelinees"
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}