package com.trevorism.service

import com.google.gson.Gson
import com.trevorism.data.Repository
import com.trevorism.data.model.filtering.SimpleFilter
import com.trevorism.entitlement.Checkout
import com.trevorism.entitlement.CheckoutRequest
import com.trevorism.entitlement.Entitlement
import com.trevorism.entitlement.EntitlementState
import com.trevorism.entitlement.TenantEntitlementProvider
import com.trevorism.https.SecureHttpClient
import com.trevorism.model.Tenant
import com.trevorism.model.TenantBillingMode
import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import com.trevorism.model.TenantRequestStatus
import com.trevorism.model.TenantStatus
import io.micronaut.security.authentication.Authentication
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertThrows

class DefaultTenantProvisioningServiceTest {

    private static final String OWNER_ID = "user-1"
    private static final String PROVIDER = "STRIPE"
    private static final String USER_ME_URL = "https://auth.trevorism.com/user/me"

    private Gson gson = new Gson()
    private List<Map> appPosts = []
    private Map<String, String> passThruGets = [:]
    private Map<String, Object> entitlementOverrides = [:]
    private Closure appPostListener = null
    private Set<String> appPostFailures = [] as Set

    @Test
    void testRequestTenantPersistsAPendingRequestWithOwnerDetails() {
        passThruGets[USER_ME_URL] = '{"id":"user-1","username":"trevor","email":"trevor@example.com"}'
        TenantRequest created = null
        def service = buildService(
                requestRepository([], { TenantRequest r -> created = r; r.id = "req-1"; r }),
                tenantRepository([]))

        TenantRequest result = service.requestTenant(new TenantRequestInput(name: " Acme ", domain: " ACME.com "), auth(OWNER_ID))

        assert result.id == "req-1"
        assert created.name == "Acme"
        assert created.domain == "acme.com"
        assert created.status == TenantRequestStatus.PENDING_PAYMENT
        assert created.ownerUserId == OWNER_ID
        assert created.ownerUsername == "trevor"
        assert created.ownerEmail == "trevor@example.com"
        assert created.dateCreated
        assert !created.tenantGuid
        assert !created.billingProvider
    }

    @Test
    void testRequestTenantRejectsADomainAlreadyInUse() {
        passThruGets[USER_ME_URL] = '{"id":"user-1","username":"trevor","email":"trevor@example.com"}'
        def service = buildService(requestRepository([]), tenantRepository([new Tenant(name: "Other", domain: "acme.com")]))

        assertThrows(TenantRequestException) {
            service.requestTenant(new TenantRequestInput(name: "Acme", domain: "acme.com"), auth(OWNER_ID))
        }
    }

    @Test
    void testRequestTenantRejectsAnUnresolvableOwner() {
        passThruGets[USER_ME_URL] = '{"id":"user-1"}'
        def service = buildService(requestRepository([]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.requestTenant(new TenantRequestInput(name: "Acme", domain: "acme.com"), auth(OWNER_ID))
        }
    }

    @Test
    void testGetRequestForCallerReturnsTheOwnedRequest() {
        TenantRequest owned = pendingRequest()
        def service = buildService(requestRepository([owned]), tenantRepository([]))

        assert service.getRequestForCaller(auth(OWNER_ID)).is(owned)
    }

    @Test
    void testGetRequestForCallerPrefersTheLiveRequestOverASuspendedOne() {
        TenantRequest suspended = pendingRequest()
        suspended.id = "req-0"
        suspended.status = TenantRequestStatus.SUSPENDED
        TenantRequest live = pendingRequest()
        def service = buildService(requestRepository([suspended, live]), tenantRepository([]))

        assert service.getRequestForCaller(auth(OWNER_ID)).is(live)
    }

    @Test
    void testGetRequestForCallerFallsBackToTheSuspendedRequest() {
        TenantRequest suspended = provisionedRequest()
        suspended.status = TenantRequestStatus.SUSPENDED
        def service = buildService(requestRepository([suspended]), tenantRepository([]))

        assert service.getRequestForCaller(auth(OWNER_ID)).is(suspended)
    }

    @Test
    void testCreateCheckoutSessionAsksTheProviderForTheTenDollarPlan() {
        CheckoutRequest captured = null
        entitlementOverrides.startCheckout = { CheckoutRequest r, Authentication a ->
            captured = r
            new Checkout(id: "cs_test_123", url: "https://checkout.example/cs_test_123")
        }
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        Map session = service.createCheckoutSession("req-1", auth(OWNER_ID))

        assert session.id == "cs_test_123"
        assert session.url == "https://checkout.example/cs_test_123"
        assert captured.monthlyPriceDollars == 10.0d
        assert captured.planName == "Trevorism Tenant: Acme"
        assert captured.successUrl == "https://trevorism.com/tenant?request=req-1&status=success"
        assert captured.cancelUrl == "https://trevorism.com/tenant?request=req-1&status=cancelled"
    }

    @Test
    void testCreateCheckoutSessionRefusesWhenTheCallerAlreadyPays() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        boolean startedCheckout = false
        entitlementOverrides.startCheckout = { CheckoutRequest r, Authentication a ->
            startedCheckout = true
            new Checkout(id: "cs_test_123", url: "https://checkout.example/cs_test_123")
        }
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.createCheckoutSession("req-1", auth(OWNER_ID))
        }
        assert !startedCheckout
    }

    @Test
    void testCreateCheckoutSessionRefusesToRestartAPaidSuspendedRequest() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        TenantRequest suspended = provisionedRequest()
        suspended.status = TenantRequestStatus.SUSPENDED
        def service = buildService(requestRepository([suspended]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.createCheckoutSession("req-1", auth(OWNER_ID))
        }
    }

    @Test
    void testCreateCheckoutSessionProceedsWhenTheEntitlementStateIsUnknown() {
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assert service.createCheckoutSession("req-1", auth(OWNER_ID)).id == "cs_test_123"
    }

    @Test
    void testCreateCheckoutSessionProceedsWhenThereIsNoLiveSubscription() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.inactive(PROVIDER, "cus_1") }
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assert service.createCheckoutSession("req-1", auth(OWNER_ID)).id == "cs_test_123"
    }

    @Test
    void testCreateCheckoutSessionRejectsARequestOwnedBySomebodyElse() {
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.createCheckoutSession("req-1", auth("intruder"))
        }
    }

    @Test
    void testCreateCheckoutSessionRejectsAnAlreadyProvisionedRequest() {
        def service = buildService(requestRepository([provisionedRequest()]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.createCheckoutSession("req-1", auth(OWNER_ID))
        }
    }

    @Test
    void testProvisionRequiresAnActiveEntitlement() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.inactive(PROVIDER, "cus_1") }
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionTreatsAnUndeterminedEntitlementAsUnpaid() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.unknown(PROVIDER) }
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionRejectsAnEntitlementWithoutABillingReference() {
        entitlementOverrides.forCaller = { Authentication a ->
            new Entitlement(provider: PROVIDER, entitlementId: "sub_1", state: EntitlementState.ACTIVE)
        }
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionCreatesTheTenantAndPromotesTheOwnerToTenantAdmin() {
        Date renewal = new Date()
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", renewal) }
        TenantRequest owned = pendingRequest()
        Tenant createdTenant = null
        TenantRequest updated = null
        def service = buildService(
                requestRepository([owned], null, { String id, TenantRequest r -> updated = r; r }),
                tenantRepository([], { Tenant t -> createdTenant = t; t }))

        TenantRequest result = service.provision("req-1", auth(OWNER_ID))

        assert createdTenant.name == "Acme"
        assert createdTenant.domain == "acme.com"
        assert createdTenant.billingMode == TenantBillingMode.SUBSCRIPTION
        assert UUID.fromString(createdTenant.guid)

        Map registration = gson.fromJson(appPosts[0].body as String, Map)
        assert appPosts[0].url == "https://auth.trevorism.com/user/"
        assert registration.username == "trevor"
        assert registration.email == "trevor@example.com"
        assert registration.tenantGuid == createdTenant.guid
        assert registration.autoRegister
        assert registration.permissions == "CRUDE"
        assert registration.password

        Map activation = gson.fromJson(appPosts[1].body as String, Map)
        assert appPosts[1].url == "https://auth.trevorism.com/user/activate"
        assert activation.isAdmin
        assert activation.tenantGuid == createdTenant.guid

        assert appPosts[2].url == "https://auth.trevorism.com/user/reset"

        assert result.status == TenantRequestStatus.PROVISIONED
        assert updated.tenantGuid == createdTenant.guid
        assert updated.billingProvider == PROVIDER
        assert updated.billingReference == "cus_1"
        assert updated.entitlementId == "sub_1"
        assert updated.paidThrough == renewal
        assert updated.dateProvisioned
    }

    @Test
    void testProvisionIsIdempotent() {
        def service = buildService(requestRepository([provisionedRequest()]), tenantRepository([]))

        TenantRequest result = service.provision("req-1", auth(OWNER_ID))

        assert result.status == TenantRequestStatus.PROVISIONED
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionRejectsAnEntitlementThatAlreadyFundsAnotherTenant() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        TenantRequest other = new TenantRequest(id: "req-2", entitlementId: "sub_1", status: TenantRequestStatus.PROVISIONED)
        def service = buildService(requestRepository([pendingRequest(), other]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionRejectsAnEntitlementWhoseEarlierTenantIsMerelySuspended() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        TenantRequest other = new TenantRequest(id: "req-2", entitlementId: "sub_1", status: TenantRequestStatus.SUSPENDED)
        def service = buildService(requestRepository([pendingRequest(), other]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testDeleteRequestRemovesAnAbandonedRequest() {
        List<String> deleted = []
        def service = buildService(requestRepository([pendingRequest()], null, null, deleted), tenantRepository([]))

        service.deleteRequest("req-1")

        assert deleted == ["req-1"]
    }

    @Test
    void testDeleteRequestRefusesToOrphanAProvisionedTenant() {
        List<String> deleted = []
        def service = buildService(requestRepository([provisionedRequest()], null, null, deleted),
                tenantRepository([acmeTenant()]))

        assertThrows(TenantRequestException) {
            service.deleteRequest("req-1")
        }
        assert deleted.isEmpty()
    }

    @Test
    void testDeleteRequestSucceedsOnceTheTenantIsGone() {
        List<String> deleted = []
        def service = buildService(requestRepository([provisionedRequest()], null, null, deleted), tenantRepository([]))

        service.deleteRequest("req-1")

        assert deleted == ["req-1"]
    }

    @Test
    void testDeleteRequestRejectsAnUnknownRequest() {
        def service = buildService(requestRepository([]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.deleteRequest("missing")
        }
    }

    @Test
    void testAFirstLapseStartsTheGracePeriodWithoutSuspending() {
        entitlementOverrides.forReference = { String reference -> Entitlement.inactive(PROVIDER, reference) }
        TenantRequest updated = null
        def service = buildService(
                requestRepository([provisionedRequest()], null, { String id, TenantRequest r -> updated = r; r }),
                tenantRepository([]))

        assert service.synchronizeEntitlements().updated == 1
        assert appPosts.isEmpty()
        assert updated.status == TenantRequestStatus.PROVISIONED
        assert updated.dateLapsed
    }

    @Test
    void testATenantIsLeftAloneWhileTheGracePeriodRuns() {
        entitlementOverrides.forReference = { String reference -> Entitlement.inactive(PROVIDER, reference) }
        TenantRequest lapsed = provisionedRequest()
        lapsed.dateLapsed = daysAgo(6)
        def service = buildService(requestRepository([lapsed]), tenantRepository([acmeTenant()]))

        assert service.synchronizeEntitlements().updated == 0
        assert appPosts.isEmpty()
        assert lapsed.status == TenantRequestStatus.PROVISIONED
    }

    @Test
    void testSynchronizeEntitlementsSuspendsOnceTheGracePeriodExpires() {
        entitlementOverrides.forReference = { String reference -> Entitlement.inactive(PROVIDER, reference) }
        TenantRequest lapsed = provisionedRequest()
        lapsed.dateLapsed = daysAgo(8)
        Tenant tenant = acmeTenant()
        TenantRequest updated = null
        def service = buildService(
                requestRepository([lapsed], null, { String id, TenantRequest r -> updated = r; r }),
                tenantRepository([tenant]))

        assert service.synchronizeEntitlements().updated == 1
        assert appPosts[0].url == "https://auth.trevorism.com/user/deactivate"
        assert updated.status == TenantRequestStatus.SUSPENDED
        assert tenant.status == TenantStatus.SUSPENDED
    }

    @Test
    void testARecoveredSubscriptionClearsTheGracePeriod() {
        entitlementOverrides.forReference = { String reference -> Entitlement.active(PROVIDER, reference, "sub_1", null) }
        TenantRequest lapsed = provisionedRequest()
        lapsed.dateLapsed = daysAgo(3)
        TenantRequest updated = null
        def service = buildService(
                requestRepository([lapsed], null, { String id, TenantRequest r -> updated = r; r }),
                tenantRepository([acmeTenant()]))

        assert service.synchronizeEntitlements().updated == 1
        assert appPosts.isEmpty()
        assert updated.status == TenantRequestStatus.PROVISIONED
        assert !updated.dateLapsed
    }

    @Test
    void testRestoringASuspendedTenantClearsTheGracePeriod() {
        entitlementOverrides.forReference = { String reference -> Entitlement.active(PROVIDER, reference, "sub_1", null) }
        TenantRequest suspended = provisionedRequest()
        suspended.status = TenantRequestStatus.SUSPENDED
        suspended.dateLapsed = daysAgo(20)
        TenantRequest updated = null
        def service = buildService(
                requestRepository([suspended], null, { String id, TenantRequest r -> updated = r; r }),
                tenantRepository([acmeTenant()]))

        assert service.synchronizeEntitlements().updated == 1
        assert updated.status == TenantRequestStatus.PROVISIONED
        assert !updated.dateLapsed
    }

    @Test
    void testSynchronizeEntitlementsRestoresARenewedTenant() {
        Date renewal = new Date()
        entitlementOverrides.forReference = { String reference -> Entitlement.active(PROVIDER, reference, "sub_1", renewal) }
        TenantRequest suspended = provisionedRequest()
        suspended.status = TenantRequestStatus.SUSPENDED
        TenantRequest updated = null
        def service = buildService(
                requestRepository([suspended], null, { String id, TenantRequest r -> updated = r; r }),
                tenantRepository([]))

        assert service.synchronizeEntitlements().updated == 1
        Map activation = gson.fromJson(appPosts[0].body as String, Map)
        assert appPosts[0].url == "https://auth.trevorism.com/user/activate"
        assert activation.isAdmin
        assert updated.status == TenantRequestStatus.PROVISIONED
        assert updated.paidThrough == renewal
    }

    @Test
    void testSynchronizeEntitlementsLeavesAHealthyTenantAlone() {
        entitlementOverrides.forReference = { String reference -> Entitlement.active(PROVIDER, reference, "sub_1", null) }
        def service = buildService(requestRepository([provisionedRequest()]), tenantRepository([]))

        assert service.synchronizeEntitlements().updated == 0
        assert appPosts.isEmpty()
    }

    @Test
    void testSynchronizeEntitlementsSkipsWhenEntitlementStateCannotBeRead() {
        TenantRequest provisioned = provisionedRequest()
        def service = buildService(requestRepository([provisioned]), tenantRepository([]))

        assert service.synchronizeEntitlements().updated == 0
        assert appPosts.isEmpty()
        assert provisioned.status == TenantRequestStatus.PROVISIONED
    }

    @Test
    void testSynchronizeEntitlementsNeverTouchesUnbilledTenants() {
        Tenant personalProject = new Tenant(id: "t-1", name: "Sandbox", domain: "sandbox.test",
                guid: "guid-unbilled", billingMode: TenantBillingMode.UNBILLED)
        Tenant legacyTenant = new Tenant(id: "t-2", name: "Legacy", domain: "legacy.test", guid: "guid-legacy")
        def service = buildService(requestRepository([]), tenantRepository([personalProject, legacyTenant]))

        Map result = service.synchronizeEntitlements()

        assert result.reviewed == 0
        assert result.updated == 0
        assert result.unmanaged == 0
        assert appPosts.isEmpty()
    }

    @Test
    void testSynchronizeEntitlementsReportsASubscriptionTenantWithNoBackingRequest() {
        Tenant orphan = new Tenant(id: "t-3", name: "Orphan", domain: "orphan.test",
                guid: "guid-orphan", billingMode: TenantBillingMode.SUBSCRIPTION)
        def service = buildService(requestRepository([]), tenantRepository([orphan]))

        assert service.synchronizeEntitlements().unmanaged == 1
    }

    @Test
    void testSynchronizeEntitlementsDoesNotReportATrackedSubscriptionTenant() {
        entitlementOverrides.forReference = { String reference -> Entitlement.active(PROVIDER, reference, "sub_1", null) }
        Tenant tracked = new Tenant(id: "t-4", name: "Acme", domain: "acme.com",
                guid: "guid-1", billingMode: TenantBillingMode.SUBSCRIPTION)
        def service = buildService(requestRepository([provisionedRequest()]), tenantRepository([tracked]))

        Map result = service.synchronizeEntitlements()

        assert result.reviewed == 1
        assert result.unmanaged == 0
    }

    @Test
    void testSynchronizeEntitlementsIgnoresRequestsAwaitingPayment() {
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assert service.synchronizeEntitlements().updated == 0
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionReportsAMissingRequestWhenTheLookupBlowsUp() {
        def service = buildService(throwingRequestRepository(), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("zz-no-such-request", auth(OWNER_ID))
        }
    }

    @Test
    void testCreateCheckoutSessionReportsAMissingRequestWhenTheLookupBlowsUp() {
        def service = buildService(throwingRequestRepository(), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.createCheckoutSession("zz-no-such-request", auth(OWNER_ID))
        }
    }

    @Test
    void testDeleteRequestReportsAMissingRequestWhenTheLookupBlowsUp() {
        def service = buildService(throwingRequestRepository(), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.deleteRequest("zz-no-such-request")
        }
    }

    @Test
    void testRequestTenantReportsABadRequestWhenTheAccountLookupFails() {
        def service = buildService(requestRepository([]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.requestTenant(new TenantRequestInput(name: "Acme", domain: "acme.com"), auth(OWNER_ID))
        }
    }

    @Test
    void testAFederatedSignInIsToldToUseATrevorismAccount() {
        def service = buildService(requestRepository([]), tenantRepository([]))

        TenantRequestException e = assertThrows(TenantRequestException) {
            service.requestTenant(new TenantRequestInput(name: "Acme", domain: "acme.com"), oauthAuth("GOOGLE"))
        }

        assert e.message.contains("GOOGLE")
        assert e.message.contains("Trevorism username and password")
    }

    @Test
    void testReadingTheCurrentRequestFromAFederatedSignInIsRefusedClearly() {
        def service = buildService(requestRepository([]), tenantRepository([]))

        TenantRequestException e = assertThrows(TenantRequestException) {
            service.getRequestForCaller(oauthAuth("GOOGLE"))
        }

        assert e.message.contains("GOOGLE")
    }

    @Test
    void testAnUnidentifiableCallerStillGetsTheGenericRefusal() {
        def service = buildService(requestRepository([]), tenantRepository([]))

        TenantRequestException e = assertThrows(TenantRequestException) {
            service.getRequestForCaller(auth(null))
        }

        assert e.message == "Unable to identify the requesting user"
    }

    @Test
    void testGetRequestForCallerReturnsNothingWhenTheCallerHasNoRequests() {
        def service = buildService(requestRepository([]), tenantRepository([]))

        assert service.getRequestForCaller(auth(OWNER_ID)) == null
    }

    @Test
    void testProvisionRecordsTheTenantBeforeCallingTheAuthService() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        List<String> sequence = []
        Tenant created = null
        def requests = requestRepository([pendingRequest()], null,
                { String id, TenantRequest r -> sequence << "checkpoint:${r.tenantGuid}".toString(); r })
        def service = buildService(requests, tenantRepository([], { Tenant t -> created = t; t }))
        appPostListener = { String url -> sequence << url }

        service.provision("req-1", auth(OWNER_ID))

        assert sequence.first() == "checkpoint:${created.guid}".toString()
        assert sequence[1] == "https://auth.trevorism.com/user/"
    }

    @Test
    void testProvisionResumesAfterTheAuthServiceFailedMidway() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        TenantRequest halfProvisioned = pendingRequest()
        halfProvisioned.tenantGuid = "guid-1"
        Tenant existing = new Tenant(id: "t-1", name: "Acme", domain: "acme.com", guid: "guid-1",
                billingMode: TenantBillingMode.SUBSCRIPTION, status: TenantStatus.ACTIVE)
        List<Tenant> created = []
        def service = buildService(requestRepository([halfProvisioned]),
                tenantRepository([existing], { Tenant t -> created << t; t }))

        TenantRequest result = service.provision("req-1", auth(OWNER_ID))

        assert created.isEmpty()
        assert result.tenantGuid == "guid-1"
        assert result.status == TenantRequestStatus.PROVISIONED
        assert appPosts.collect { it.url } == ["https://auth.trevorism.com/user/",
                                               "https://auth.trevorism.com/user/activate",
                                               "https://auth.trevorism.com/user/reset"]
    }

    @Test
    void testResumingToleratesAnAdministratorThatWasAlreadyRegistered() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        appPostFailures << "https://auth.trevorism.com/user/"
        TenantRequest halfProvisioned = pendingRequest()
        halfProvisioned.tenantGuid = "guid-1"
        def service = buildService(requestRepository([halfProvisioned]), tenantRepository([acmeTenant()]))

        TenantRequest result = service.provision("req-1", auth(OWNER_ID))

        assert result.status == TenantRequestStatus.PROVISIONED
        assert appPosts.collect { it.url } == ["https://auth.trevorism.com/user/",
                                               "https://auth.trevorism.com/user/activate",
                                               "https://auth.trevorism.com/user/reset"]
    }

    @Test
    void testAFailedActivationStillStopsProvisioning() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        appPostFailures << "https://auth.trevorism.com/user/activate"
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([]))

        assertThrows(RuntimeException) {
            service.provision("req-1", auth(OWNER_ID))
        }
    }

    @Test
    void testProvisionRestoresTheExistingTenantWhenTheRequestWasSuspended() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        TenantRequest suspended = provisionedRequest()
        suspended.status = TenantRequestStatus.SUSPENDED
        Tenant existing = new Tenant(id: "t-1", name: "Acme", domain: "acme.com", guid: "guid-1",
                billingMode: TenantBillingMode.SUBSCRIPTION, status: TenantStatus.SUSPENDED)
        List<Tenant> created = []
        def service = buildService(requestRepository([suspended]), tenantRepository([existing], { Tenant t -> created << t; t }))

        TenantRequest result = service.provision("req-1", auth(OWNER_ID))

        assert created.isEmpty()
        assert result.tenantGuid == "guid-1"
        assert result.status == TenantRequestStatus.PROVISIONED
        assert existing.status == TenantStatus.ACTIVE
        assert appPosts.size() == 1
        assert appPosts[0].url == "https://auth.trevorism.com/user/activate"
    }

    @Test
    void testProvisionRefusesWhenTheSuspendedTenantNoLongerExists() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        TenantRequest suspended = provisionedRequest()
        suspended.status = TenantRequestStatus.SUSPENDED
        def service = buildService(requestRepository([suspended]), tenantRepository([]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionRefusesADomainClaimedWhileTheRequestAwaitedPayment() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        Tenant claimed = new Tenant(id: "t-9", name: "Rival", domain: "acme.com", guid: "guid-rival",
                billingMode: TenantBillingMode.SUBSCRIPTION, status: TenantStatus.ACTIVE)
        List<Tenant> created = []
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([claimed], { Tenant t -> created << t; t }))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert created.isEmpty()
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionRefusesANameClaimedWhileTheRequestAwaitedPayment() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        Tenant claimed = new Tenant(id: "t-9", name: "Acme", domain: "other.com", guid: "guid-rival",
                billingMode: TenantBillingMode.SUBSCRIPTION, status: TenantStatus.ACTIVE)
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([claimed]))

        assertThrows(TenantRequestException) {
            service.provision("req-1", auth(OWNER_ID))
        }
        assert appPosts.isEmpty()
    }

    @Test
    void testProvisionMarksANewTenantActive() {
        entitlementOverrides.forCaller = { Authentication a -> Entitlement.active(PROVIDER, "cus_1", "sub_1", null) }
        Tenant created = null
        def service = buildService(requestRepository([pendingRequest()]), tenantRepository([], { Tenant t -> created = t; t }))

        service.provision("req-1", auth(OWNER_ID))

        assert created.status == TenantStatus.ACTIVE
        assert created.billingMode == TenantBillingMode.SUBSCRIPTION
    }

    @Test
    void testDeleteRequestRefusesToOrphanASuspendedTenant() {
        TenantRequest suspended = provisionedRequest()
        suspended.status = TenantRequestStatus.SUSPENDED
        List<String> deleted = []
        def service = buildService(requestRepository([suspended], null, null, deleted),
                tenantRepository([acmeTenant()]))

        assertThrows(TenantRequestException) {
            service.deleteRequest("req-1")
        }
        assert deleted.isEmpty()
    }

    @Test
    void testSynchronizeEntitlementsMarksTheTenantActiveOnRestore() {
        entitlementOverrides.forReference = { String reference -> Entitlement.active(PROVIDER, reference, "sub_1", null) }
        TenantRequest suspended = provisionedRequest()
        suspended.status = TenantRequestStatus.SUSPENDED
        Tenant tenant = new Tenant(id: "t-1", name: "Acme", domain: "acme.com", guid: "guid-1",
                billingMode: TenantBillingMode.SUBSCRIPTION, status: TenantStatus.SUSPENDED)
        def service = buildService(requestRepository([suspended]), tenantRepository([tenant]))

        assert service.synchronizeEntitlements().updated == 1
        assert tenant.status == TenantStatus.ACTIVE
    }

    private static Date daysAgo(int days) {
        return new Date(System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L))
    }

    private static Tenant acmeTenant() {
        return new Tenant(id: "t-1", name: "Acme", domain: "acme.com", guid: "guid-1",
                billingMode: TenantBillingMode.SUBSCRIPTION, status: TenantStatus.ACTIVE)
    }

    private static TenantRequest pendingRequest() {
        return new TenantRequest(id: "req-1", name: "Acme", domain: "acme.com",
                status: TenantRequestStatus.PENDING_PAYMENT, ownerUserId: OWNER_ID,
                ownerUsername: "trevor", ownerEmail: "trevor@example.com", dateCreated: new Date())
    }

    private static TenantRequest provisionedRequest() {
        TenantRequest request = pendingRequest()
        request.status = TenantRequestStatus.PROVISIONED
        request.tenantGuid = "guid-1"
        request.billingProvider = PROVIDER
        request.billingReference = "cus_1"
        request.entitlementId = "sub_1"
        request.dateProvisioned = new Date()
        return request
    }

    private static Authentication auth(String id) {
        [getName: { "caller" }, getRoles: { ["user"] }, getAttributes: { [id: id] }] as Authentication
    }

    private static Authentication oauthAuth(String provider) {
        [getName      : { "caller@example.com" },
         getRoles     : { ["Oauth2"] },
         getAttributes: { [email: "caller@example.com", provider: provider, permissions: "R"] }] as Authentication
    }

    private DefaultTenantProvisioningService buildService(Repository<TenantRequest> requests, Repository<Tenant> tenants) {
        DefaultTenantProvisioningService service = new DefaultTenantProvisioningService(
                stubClient(passThruGets, []), stubClient([:], appPosts), stubEntitlementProvider())
        service.tenantRequestRepository = requests
        service.tenantRepository = tenants
        return service
    }

    private TenantEntitlementProvider stubEntitlementProvider() {
        Map behaviour = [
                getName      : { PROVIDER },
                startCheckout: { CheckoutRequest r, Authentication a -> new Checkout(id: "cs_test_123", url: "https://checkout.example/cs_test_123") },
                forCaller    : { Authentication a -> Entitlement.unknown(PROVIDER) },
                forReference : { String reference -> Entitlement.unknown(PROVIDER) }
        ]
        return (behaviour + entitlementOverrides) as TenantEntitlementProvider
    }

    private SecureHttpClient stubClient(Map<String, String> gets, List<Map> posts) {
        return [
                get : { String url ->
                    if (!gets.containsKey(url)) {
                        throw new RuntimeException("unexpected GET ${url}")
                    }
                    return gets[url]
                },
                post: { String url, String body ->
                    posts << [url: url, body: body]
                    appPostListener?.call(url)
                    if (appPostFailures.contains(url)) {
                        throw new RuntimeException("simulated auth failure for ${url}")
                    }
                    return "{}"
                }
        ] as SecureHttpClient
    }

    private static Repository<TenantRequest> requestRepository(List<TenantRequest> stored,
                                                               Closure<TenantRequest> onCreate = null,
                                                               Closure<TenantRequest> onUpdate = null,
                                                               List<String> deleted = []) {
        return [
                list  : { stored },
                get   : { String id -> stored.find { it.id == id } },
                filter: { SimpleFilter filter -> stored.findAll { it[filter.field] == filter.value } },
                create: { TenantRequest request -> onCreate ? onCreate.call(request) : request },
                update: { String id, TenantRequest request -> onUpdate ? onUpdate.call(id, request) : request },
                delete: { String id ->
                    deleted << id
                    return stored.find { it.id == id }
                }
        ] as Repository
    }

    private static Repository<TenantRequest> throwingRequestRepository() {
        return [
                list  : { [] },
                get   : { String id -> throw new RuntimeException("Unable to HTTP GET: /object/tenantRequest/${id}") },
                filter: { SimpleFilter filter -> [] },
                create: { TenantRequest request -> request },
                update: { String id, TenantRequest request -> request },
                delete: { String id -> null }
        ] as Repository
    }

    private static Repository<Tenant> tenantRepository(List<Tenant> stored, Closure<Tenant> onCreate = null) {
        return [
                list  : { stored },
                filter: { SimpleFilter filter -> stored.findAll { it[filter.field] == filter.value } },
                create: { Tenant tenant -> onCreate ? onCreate.call(tenant) : tenant },
                update: { String id, Tenant tenant -> tenant }
        ] as Repository
    }
}
