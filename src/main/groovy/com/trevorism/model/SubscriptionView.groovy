package com.trevorism.model

import com.trevorism.entitlement.Entitlement
import com.trevorism.entitlement.EntitlementState

class SubscriptionView {

    String provider
    String state
    Date paidThrough

    static SubscriptionView from(Entitlement entitlement) {
        if (!entitlement) {
            return new SubscriptionView(state: EntitlementState.UNKNOWN.name())
        }
        return new SubscriptionView(
                provider: entitlement.provider,
                state: (entitlement.state ?: EntitlementState.UNKNOWN).name(),
                paidThrough: entitlement.paidThrough)
    }
}
