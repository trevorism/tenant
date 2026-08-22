package com.trevorism.model

class TenantBillingMode {

    static final String UNBILLED = "UNBILLED"
    static final String SUBSCRIPTION = "SUBSCRIPTION"

    static String orDefault(String billingMode) {
        return billingMode ?: UNBILLED
    }

    static boolean isSubscription(String billingMode) {
        return orDefault(billingMode) == SUBSCRIPTION
    }
}
