package com.sync.cdc.sqlserver;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * SQL Server LSN wrapper: immutable {@code binary(10)} value with unsigned ordering
 * (the native ordering for SQL Server LSNs).
 */
public record ByteArrayPosition(byte[] bytes) implements Comparable<ByteArrayPosition> {

    public ByteArrayPosition {
        Objects.requireNonNull(bytes, "bytes");
    }

    @Override
    public int compareTo(ByteArrayPosition other) {
        return Arrays.compareUnsigned(this.bytes, other.bytes);
    }

    public String toHex() {
        return HexFormat.of().formatHex(bytes);
    }

    public static ByteArrayPosition fromHex(String hex) {
        return new ByteArrayPosition(HexFormat.of().parseHex(hex));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ByteArrayPosition that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "0x" + toHex();
    }
}
