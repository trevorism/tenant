package com.trevorism.controller

import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import com.trevorism.model.TenantRequestStatus
import com.trevorism.secure.Roles
import com.trevorism.secure.Secure
import com.trevorism.service.TenantProvisioningService
import com.trevorism.service.TenantRequestException
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import org.junit.jupiter.api.Test

class SubscribedTenantControllerTest {

    @Test
    void testRequestTenantDelegatesToTheService() {
        TenantRequest expected = new TenantRequest(id: "req-1", status: TenantRequestStatus.PENDING_PAYMENT)
        SubscribedTenantController controller = controllerWith([
                requestTenant: { TenantRequestInput input, Authentication auth -> expected }
        ])

        def view = controller.requestTenant(new TenantRequestInput(name: "Acme", domain: "acme.com"), authentication())

        assert view.id == "req-1"
        assert view.status == TenantRequestStatus.PENDING_PAYMENT
    }

    @Test
    void testRequestTenantTranslatesValidationFailureToBadRequest() {
        SubscribedTenantController controller = controllerWith([
                requestTenant: { TenantRequestInput input, Authentication auth ->
                    throw new TenantRequestException("Domain acme.com is reserved")
                }
        ])

        try {
            controller.requestTenant(new TenantRequestInput(), authentication())
            assert false
        } catch (HttpStatusException e) {
            assert e.status == HttpStatus.BAD_REQUEST
            assert e.message == "Domain acme.com is reserved"
        }
    }

    @Test
    void testGetCurrentRequestReturnsNoContentWhenTheCallerHasNone() {
        SubscribedTenantController controller = controllerWith([getRequestForCaller: { Authentication auth -> null }])

        assert controller.getCurrentRequest(authentication()).status == HttpStatus.NO_CONTENT
    }

    @Test
    void testGetCurrentRequestReturnsTheOwnedRequest() {
        TenantRequest expected = new TenantRequest(id: "req-1")
        SubscribedTenantController controller = controllerWith([getRequestForCaller: { Authentication auth -> expected }])

        def response = controller.getCurrentRequest(authentication())

        assert response.status == HttpStatus.OK
        assert response.body().id == "req-1"
    }

    @Test
    void testGetCurrentRequestNeverExposesBillingIdentifiers() {
        TenantRequest stored = new TenantRequest(id: "req-1", status: TenantRequestStatus.PROVISIONED,
                tenantGuid: "guid-1", billingProvider: "STRIPE", billingReference: "cus_1", entitlementId: "sub_1")
        SubscribedTenantController controller = controllerWith([getRequestForCaller: { Authentication auth -> stored }])

        def body = controller.getCurrentRequest(authentication()).body()

        assert !(body instanceof TenantRequest)
        assert !body.properties.containsKey("billingReference")
        assert !body.properties.containsKey("entitlementId")
        assert body.tenantGuid == "guid-1"
    }

    @Test
    void testCreateCheckoutSessionReturnsTheStripeSession() {
        SubscribedTenantController controller = controllerWith([
                createCheckoutSession: { String requestId, Authentication auth -> [id: "cs_test_123"] }
        ])

        assert controller.createCheckoutSession("req-1", authentication()).id == "cs_test_123"
    }

    @Test
    void testProvisionReturnsTheProvisionedRequest() {
        TenantRequest expected = new TenantRequest(id: "req-1", status: TenantRequestStatus.PROVISIONED, tenantGuid: "guid-1")
        SubscribedTenantController controller = controllerWith([
                provision: { String requestId, Authentication auth -> expected }
        ])

        def view = controller.provision("req-1", authentication())

        assert view.id == "req-1"
        assert view.status == TenantRequestStatus.PROVISIONED
        assert view.loginUrl == "https://login.auth.trevorism.com/guid-1"
    }

    @Test
    void testProvisionTranslatesAMissingSubscriptionToBadRequest() {
        SubscribedTenantController controller = controllerWith([
                provision: { String requestId, Authentication auth ->
                    throw new TenantRequestException("An active subscription is required")
                }
        ])

        try {
            controller.provision("req-1", authentication())
            assert false
        } catch (HttpStatusException e) {
            assert e.status == HttpStatus.BAD_REQUEST
        }
    }

    @Test
    void testCreateBillingPortalSessionReturnsTheProvidersUrl() {
        SubscribedTenantController controller = controllerWith([
                createBillingPortalSession: { Authentication auth -> [url: "https://billing.example/session"] }
        ])

        assert controller.createBillingPortalSession(authentication()).url == "https://billing.example/session"
    }

    @Test
    void testCreateBillingPortalSessionTranslatesAFailureToBadRequest() {
        SubscribedTenantController controller = controllerWith([
                createBillingPortalSession: { Authentication auth ->
                    throw new TenantRequestException("Unable to reach the billing provider")
                }
        ])

        try {
            controller.createBillingPortalSession(authentication())
            assert false
        } catch (HttpStatusException e) {
            assert e.status == HttpStatus.BAD_REQUEST
        }
    }

    @Test
    void testTheBillingPortalIsReachableByAnOrdinaryUser() {
        Secure secure = SubscribedTenantController.getMethod("createBillingPortalSession", Authentication)
                .getAnnotation(Secure)

        assert secure.value() == Roles.USER
    }

    @Test
    void testSweepReportsWhatItReviewedAndChanged() {
        SubscribedTenantController controller = controllerWith([
                synchronizeEntitlements: { [reviewed: 5, updated: 3, unmanaged: 1] }
        ])

        assert controller.synchronizeEntitlements() == [reviewed: 5, updated: 3, unmanaged: 1]
    }

    @Test
    void testReconciliationAcceptsTheSchedulersInternalToken() {
        Secure secure = SubscribedTenantController.getMethod("synchronizeEntitlements").getAnnotation(Secure)

        assert secure.value() == Roles.SYSTEM
        assert secure.allowInternal()
    }

    private static Authentication authentication() {
        [getName: { "caller" }, getRoles: { ["user"] }, getAttributes: { [id: "user-1"] }] as Authentication
    }

    private static SubscribedTenantController controllerWith(Map methods) {
        SubscribedTenantController controller = new SubscribedTenantController()
        controller.tenantProvisioningService = methods as TenantProvisioningService
        return controller
    }
}
