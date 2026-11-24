package com.crm.api.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PhoneMaskerTest {

    @Test
    void masksMiddleFourDigits() {
        assertEquals("138****0000", PhoneMasker.mask("13800000000"));
    }

    @Test
    void keepsNullAndShortValues() {
        assertNull(PhoneMasker.mask(null));
        assertEquals("123", PhoneMasker.mask("123"));
    }
}
