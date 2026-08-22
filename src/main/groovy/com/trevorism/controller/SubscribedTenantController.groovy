package com.trevorism.controller

import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import com.trevorism.model.TenantRequestView
import com.trevorism.secure.Roles
import com.trevorism.secure.Secure
import com.trevorism.service.TenantProvisioningService
import com.trevorism.service.TenantRequestException
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Status
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.inject.Inject

@Controller("/subscribedtenant")
class SubscribedTenantController {

    @Inject
    TenantProvisioningService tenantProvisioningService

    @Tag(name = "Subscribed Tenant Operations")
    @Operation(summary = "Request a new subscribed tenant for the current caller **Secure")
    @Secure(Roles.USER)
    @Post(value = "/", produces = MediaType.APPLICATION_JSON, consumes = MediaType.APPLICATION_JSON)
    @Status(HttpStatus.CREATED)
    TenantRequestView requestTenant(@Body TenantRequestInput input, Authentication authentication) {
        return TenantRequestView.from(invoke { tenantProvisioningService.requestTenant(input, authentication) })
    }

    @Tag(name = "Subscribed Tenant Operations")
    @Operation(summary = "Gets the subscribed tenant request of the current caller **Secure")
    @Secure(Roles.USER)
    @Get(value = "/me", produces = MediaType.APPLICATION_JSON)
    HttpResponse<TenantRequestView> getCurrentRequest(Authentication authentication) {
        TenantRequest request = invoke { tenantProvisioningService.getRequestForCaller(authentication) }
        return request ? HttpResponse.ok(TenantRequestView.from(request)) : HttpResponse.noContent()
    }

    @Tag(name = "Subscribed Tenant Operations")
    @Operation(summary = "Creates a subscription checkout session for a tenant request **Secure")
    @Secure(Roles.USER)
    @Post(value = "/{requestId}/session", produces = MediaType.APPLICATION_JSON)
    Map createCheckoutSession(String requestId, Authentication authentication) {
        return invoke { tenantProvisioningService.createCheckoutSession(requestId, authentication) }
    }

    @Tag(name = "Subscribed Tenant Operations")
    @Operation(summary = "Provisions the tenant once the subscription is active **Secure")
    @Secure(Roles.USER)
    @Post(value = "/{requestId}/provision", produces = MediaType.APPLICATION_JSON)
    TenantRequestView provision(String requestId, Authentication authentication) {
        return TenantRequestView.from(invoke { tenantProvisioningService.provision(requestId, authentication) })
    }

    @Tag(name = "Subscribed Tenant Operations")
    @Operation(summary = "Deletes an unprovisioned tenant request **Secure")
    @Secure(Roles.SYSTEM)
    @Delete(value = "/{requestId}", produces = MediaType.APPLICATION_JSON)
    TenantRequest deleteRequest(String requestId) {
        return invoke { tenantProvisioningService.deleteRequest(requestId) }
    }

    @Tag(name = "Subscribed Tenant Operations")
    @Operation(summary = "Suspends or restores subscribed tenants to match their subscription state **Secure")
    @Secure(Roles.SYSTEM)
    @Post(value = "/sweep", produces = MediaType.APPLICATION_JSON)
    Map synchronizeEntitlements() {
        return tenantProvisioningService.synchronizeEntitlements()
    }

    private static <T> T invoke(Closure<T> closure) {
        try {
            return closure.call()
        } catch (TenantRequestException e) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
    }
}
