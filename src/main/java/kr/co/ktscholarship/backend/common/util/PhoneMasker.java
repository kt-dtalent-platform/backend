package kr.co.ktscholarship.backend.common.util;

public final class PhoneMasker {

    private PhoneMasker() {
    }

    public static String mask(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() != 10 && digits.length() != 11) {
            return "****";
        }
        int prefixLength = digits.startsWith("02") ? 2 : 3;
        return digits.substring(0, prefixLength) + "-****-" + digits.substring(digits.length() - 4);
    }
}
