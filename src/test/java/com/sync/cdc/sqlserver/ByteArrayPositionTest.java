package com.sync.cdc.sqlserver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByteArrayPositionTest {

    @Test
    void compareTo_usesUnsignedOrdering() {
        // 0x00 < 0x7F < 0x80 < 0xFF  (unsigned)
        ByteArrayPosition zero = new ByteArrayPosition(new byte[]{0x00});
        ByteArrayPosition seven = new ByteArrayPosition(new byte[]{0x7F});
        ByteArrayPosition eight = new ByteArrayPosition(new byte[]{(byte) 0x80});
        ByteArrayPosition ffff = new ByteArrayPosition(new byte[]{(byte) 0xFF});

        assertThat(zero).isLessThan(seven);
        assertThat(seven).isLessThan(eight);
        assertThat(eight).isLessThan(ffff);
    }

    @Test
    void compareTo_signedWouldBeWrong_unsignedIsCorrect() {
        // Signed comparison would say 0x80 (-128) < 0x00 (0). Unsigned is correct.
        ByteArrayPosition high = new ByteArrayPosition(new byte[]{(byte) 0x80});
        ByteArrayPosition low = new ByteArrayPosition(new byte[]{0x00});
        assertThat(high).isGreaterThan(low);
    }

    @Test
    void equalsAndHashCode_byContent() {
        ByteArrayPosition a = new ByteArrayPosition(new byte[]{1, 2, 3});
        ByteArrayPosition b = new ByteArrayPosition(new byte[]{1, 2, 3});
        ByteArrayPosition c = new ByteArrayPosition(new byte[]{1, 2, 4});

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void hexRoundTrip() {
        ByteArrayPosition original = new ByteArrayPosition(
                new byte[]{0x00, 0x00, 0x00, 0x17, (byte) 0xAB, (byte) 0xCD, 0x01, 0x02, 0x03, 0x04});

        String hex = original.toHex();
        ByteArrayPosition decoded = ByteArrayPosition.fromHex(hex);

        assertThat(decoded).isEqualTo(original);
        assertThat(original.toString()).startsWith("0x");
    }

    @Test
    void constructor_rejectsNull() {
        assertThatThrownBy(() -> new ByteArrayPosition(null))
                .isInstanceOf(NullPointerException.class);
    }
}
