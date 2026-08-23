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
        assert !exposed.contains("ownerUserId")
        assert !exposed.contains("ownerEmail")
        assert !exposed.contains("ownerUsername")
    }

    @Test
    void testTheViewHandsTheOwnerTheirOwnTenantIdAndLoginUrl() {
        TenantRequest request = new TenantRequest(id: "req-1", status: TenantRequestStatus.PROVISIONED,
                tenantGuid: "guid-1")

        TenantRequestView view = TenantRequestView.from(request)

        assert view.tenantGuid == "guid-1"
        assert view.loginUrl == "https://login.auth.trevorism.com/guid-1"
    }

    @Test
    void testThereIsNoLoginUrlBeforeTheTenantExists() {
        TenantRequest request = new TenantRequest(id: "req-1", status: TenantRequestStatus.PENDING_PAYMENT)

        TenantRequestView view = TenantRequestView.from(request)

        assert !view.tenantGuid
        assert !view.loginUrl
    }

    @Test
    void testALapsedRequestReportsWhenAccessEnds() {
        Date lapsed = new Date()
        TenantRequest request = new TenantRequest(id: "req-1", status: TenantRequestStatus.PROVISIONED,
                tenantGuid: "guid-1", dateLapsed: lapsed)

        TenantRequestView view = TenantRequestView.from(request)

        assert view.dateLapsed == lapsed
        assert view.accessEndsOn == new Date(lapsed.time + TenantRequest.LAPSE_GRACE_MILLIS)
    }

    @Test
    void testAHealthyRequestHasNoAccessDeadline() {
        TenantRequest request = new TenantRequest(id: "req-1", status: TenantRequestStatus.PROVISIONED,
                tenantGuid: "guid-1")

        assert !TenantRequestView.from(request).accessEndsOn
    }

    @Test
    void testASuspendedRequestNoLongerCountsDownToSuspension() {
        TenantRequest request = new TenantRequest(id: "req-1", status: TenantRequestStatus.SUSPENDED,
                tenantGuid: "guid-1", dateLapsed: new Date())

        assert !TenantRequestView.from(request).accessEndsOn
    }

    @Test
    void testAMissingRequestMapsToNothing() {
        assert TenantRequestView.from(null) == null
    }
}
