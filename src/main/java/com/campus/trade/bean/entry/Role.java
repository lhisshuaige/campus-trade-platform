package com.campus.trade.bean.entry;

public enum Role {

    USER("user"),
    ADMIN("admin");

    private final String code;

    Role(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}