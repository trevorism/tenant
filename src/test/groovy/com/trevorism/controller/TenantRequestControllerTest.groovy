package com.trevorism.controller

import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import com.trevorism.model.TenantRequestStatus
import com.trevorism.service.TenantProvisioningService
import com.trevorism.service.TenantRequestException
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import org.junit.jupiter.api.Test

class TenantRequestControllerTest {

    @Test
    void testRequestTenantDelegatesToTheService() {
        TenantRequest expected = new TenantRequest(id: "req-1", status: TenantRequestStatus.PENDING_PAYMENT)
        TenantRequestController controller = controllerWith([
                requestTenant: { TenantRequestInput input, Authentication auth -> expected }
        ])

        assert controller.requestTenant(new TenantRequestInput(name: "Acme", domain: "acme.com"), authentication()).is(expected)
    }

    @Test
    void testRequestTenantTranslatesValidationFailureToBadRequest() {
        TenantRequestController controller = controllerWith([
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
        TenantRequestController controller = controllerWith([getRequestForCaller: { Authentication auth -> null }])

        assert controller.getCurrentRequest(authentication()).status == HttpStatus.NO_CONTENT
    }

    @Test
    void testGetCurrentRequestReturnsTheOwnedRequest() {
        TenantRequest expected = new TenantRequest(id: "req-1")
        TenantRequestController controller = controllerWith([getRequestForCaller: { Authentication auth -> expected }])

        def response = controller.getCurrentRequest(authentication())

        assert response.status == HttpStatus.OK
        assert response.body().is(expected)
    }

    @Test
    void testCreateCheckoutSessionReturnsTheStripeSession() {
        TenantRequestController controller = controllerWith([
                createCheckoutSession: { String requestId, Authentication auth -> [id: "cs_test_123"] }
        ])

        assert controller.createCheckoutSession("req-1", authentication()).id == "cs_test_123"
    }

    @Test
    void testProvisionReturnsTheProvisionedRequest() {
        TenantRequest expected = new TenantRequest(id: "req-1", status: TenantRequestStatus.PROVISIONED, tenantGuid: "guid-1")
        TenantRequestController controller = controllerWith([
                provision: { String requestId, Authentication auth -> expected }
        ])

        assert controller.provision("req-1", authentication()).is(expected)
    }

    @Test
    void testProvisionTranslatesAMissingSubscriptionToBadRequest() {
        TenantRequestController controller = controllerWith([
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
    void testSweepReportsTheNumberOfUpdatedTenants() {
        TenantRequestController controller = controllerWith([synchronizeEntitlements: { 3 }])

        assert controller.synchronizeEntitlements() == [updated: 3]
    }

    private static Authentication authentication() {
        [getName: { "caller" }, getRoles: { ["user"] }, getAttributes: { [id: "user-1"] }] as Authentication
    }

    private static TenantRequestController controllerWith(Map methods) {
        TenantRequestController controller = new TenantRequestController()
        controller.tenantProvisioningService = methods as TenantProvisioningService
        return controller
    }
}
