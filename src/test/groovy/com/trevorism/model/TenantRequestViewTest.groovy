package com.trevorism.model

import org.junit.jupiter.api.Test

class TenantRequestViewTest {

    @Test
    void testTheViewCarriesEverythingThePortalNeeds() {
        Date created = new Date()
        Date provisioned = new Date()
        Date paidThrough = new Date()
        TenantRequest request = new TenantRequest(id: "req-1", name: "Acme", domain: "acme.com",
                status: TenantRequestStatus.PROVISIONED, paidThrough: paidThrough,
                dateCreated: created, dateProvisioned: provisioned)

        TenantRequestView view = TenantRequestView.from(request)

        assert view.id == "req-1"
        assert view.name == "Acme"
        assert view.domain == "acme.com"
        assert view.status == TenantRequestStatus.PROVISIONED
        assert view.paidThrough == paidThrough
        assert view.dateCreated == created
        assert view.dateProvisioned == provisioned
    }

    @Test
    void testTheViewWithholdsBillingIdentifiersAndOwnerIdentity() {
        TenantRequest request = new TenantRequest(id: "req-1", ownerUserId: "user-1", ownerUsername: "trevor",
                ownerEmail: "trevor@example.com", tenantGuid: "guid-1", billingProvider: "STRIPE",
                billingReference: "cus_1", entitlementId: "sub_1")

        List<String> exposed = TenantRequestView.from(request).properties.keySet() as List

        assert !exposed.contains("billingReference")
        assert !exposed.contains("entitlementId")
        assert !exposed.contains("billingProvider")
        assert !exposed.contains("tenantGuid")
        assert !exposed.contains("ownerUserId")
        assert !exposed.contains("ownerEmail")
        assert !exposed.contains("ownerUsername")
    }

    @Test
    void testAMissingRequestMapsToNothing() {
        assert TenantRequestView.from(null) == null
    }
}
