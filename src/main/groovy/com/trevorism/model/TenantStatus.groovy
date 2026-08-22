package com.trevorism.model

class TenantStatus {

    static final String ACTIVE = "ACTIVE"
    static final String SUSPENDED = "SUSPENDED"

    static String orDefault(String status) {
        return status ?: ACTIVE
    }

    static boolean isSuspended(String status) {
        return orDefault(status) == SUSPENDED
    }
}
