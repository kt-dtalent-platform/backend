package kr.co.ktscholarship.backend.scholar;

final class ScholarPhones {
    private ScholarPhones() {}

    static String digits(String phone) {
        return phone == null ? null : phone.replaceAll("[^0-9]", "");
    }
}
