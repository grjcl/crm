package com.crm.api.common;

/**
 * 手机号展示脱敏：{@code 13800000000 -> 138****0000}。
 */
public final class PhoneMasker {

    private PhoneMasker() {
    }

    public static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
