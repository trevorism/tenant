package com.trevorism.entitlement

class Entitlement {

    String provider
    String reference
    String entitlementId
    EntitlementState state
    Date paidThrough

    boolean isActive() {
        return state == EntitlementState.ACTIVE
    }

    boolean isUndetermined() {
        return state == EntitlementState.UNKNOWN
    }

    static Entitlement active(String provider, String reference, String entitlementId, Date paidThrough) {
        return new Entitlement(provider: provider, reference: reference, entitlementId: entitlementId,
                state: EntitlementState.ACTIVE, paidThrough: paidThrough)
    }

    static Entitlement inactive(String provider, String reference) {
        return new Entitlement(provider: provider, reference: reference, state: EntitlementState.INACTIVE)
    }

    static Entitlement unknown(String provider) {
        return new Entitlement(provider: provider, state: EntitlementState.UNKNOWN)
    }
}
