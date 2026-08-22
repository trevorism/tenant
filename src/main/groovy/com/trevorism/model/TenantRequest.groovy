package com.trevorism.model

class TenantRequest {

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
}
