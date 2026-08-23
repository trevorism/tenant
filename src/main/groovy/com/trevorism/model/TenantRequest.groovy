package com.trevorism.model

class TenantRequest {

    static final int LAPSE_GRACE_DAYS = 7
    static final long LAPSE_GRACE_MILLIS = LAPSE_GRACE_DAYS * 24L * 60L * 60L * 1000L
    static final int LAPSE_REMINDER_DAYS_BEFORE_END = 2

    String id
    String name
    String domain
    String status

    String ownerUserId
    String ownerUsername
    String ownerEmail

    String tenantGuid
    String billingProvider
    String billingReference
    String entitlementId
    Date paidThrough

    Date dateCreated
    Date dateProvisioned
    Date dateLapsed
    Date dateLapseReminded

    Date accessEndsOn() {
        return dateLapsed ? new Date(dateLapsed.time + LAPSE_GRACE_MILLIS) : null
    }
}
