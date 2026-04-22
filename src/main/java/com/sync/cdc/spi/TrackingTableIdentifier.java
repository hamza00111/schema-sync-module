package com.sync.cdc.spi;

import java.util.regex.Pattern;

/**
 * Validates the configured tracking-table identifier before it is interpolated
 * into SQL. JDBC parameters can't bind table names, so we enforce a strict
 * allowlist: one or two dot-separated parts, each matching {@code [A-Za-z_][A-Za-z0-9_]*}.
 */
public final class TrackingTableIdentifier {

    private static final Pattern PATTERN =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private TrackingTableIdentifier() {}

    public static String validate(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Tracking table name must not be null or blank");
        }
        if (!PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "Invalid tracking table name: " + identifier
                            + " (allowed: <schema>.<table> or <table>, letters/digits/underscore only)");
        }
        return identifier;
    }
}
