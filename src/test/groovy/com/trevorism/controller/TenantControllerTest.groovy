package com.trevorism.controller

import com.trevorism.data.Repository
import com.trevorism.data.model.filtering.SimpleFilter
import com.trevorism.model.Tenant
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import org.junit.jupiter.api.Test

class TenantControllerTest {

    private static Authentication authentication(Map attributes) {
        [getName: { "caller" }, getRoles: { ["user"] }, getAttributes: { attributes }] as Authentication
    }

    private static TenantController controllerWith(Repository<Tenant> repository) {
        TenantController controller = new TenantController()
        controller.@tenantRepository = repository
        return controller
    }

    @Test
    void testGetCurrentTenantReturnsTheTenantMatchingTheClaim() {
        SimpleFilter captured = null
        Tenant expected = new Tenant(id: "1", name: "Acme", domain: "acme.com", guid: "guid-abc")
        TenantController controller = controllerWith([filter: { SimpleFilter filter ->
            captured = filter
            [expected]
        }] as Repository)

        HttpResponse<Tenant> actual = controller.getCurrentTenant(authentication([tenant: "guid-abc"]))

        assert actual.status == HttpStatus.OK
        assert actual.body().is(expected)
        assert captured.field == "guid"
        assert captured.value == "guid-abc"
    }

    @Test
    void testGetCurrentTenantReturnsNoContentWhenCallerHasNoTenantClaim() {
        boolean queried = false
        TenantController controller = controllerWith([filter: { SimpleFilter filter ->
            queried = true
            []
        }] as Repository)

        assert controller.getCurrentTenant(authentication([:])).status == HttpStatus.NO_CONTENT
        assert !queried
    }

    @Test
    void testGetCurrentTenantReturnsNoContentWhenClaimIsBlank() {
        TenantController controller = controllerWith([filter: { SimpleFilter filter -> [] }] as Repository)

        assert controller.getCurrentTenant(authentication([tenant: ""])).status == HttpStatus.NO_CONTENT
    }

    @Test
    void testGetCurrentTenantReturnsNotFoundWhenNoTenantMatchesTheClaim() {
        TenantController controller = controllerWith([filter: { SimpleFilter filter -> [] }] as Repository)

        assert controller.getCurrentTenant(authentication([tenant: "missing"])).status == HttpStatus.NOT_FOUND
    }

    @Test
    void testGetCurrentTenantReturnsNoContentWhenUnauthenticated() {
        TenantController controller = controllerWith([filter: { SimpleFilter filter -> [] }] as Repository)

        assert controller.getCurrentTenant(null).status == HttpStatus.NO_CONTENT
    }

    @Test
    void testListTenantDelegatesToTheRepository() {
        Tenant tenant = new Tenant(id: "1", name: "Acme")
        TenantController controller = controllerWith([list: { [tenant] }] as Repository)

        assert controller.listTenant() == [tenant]
    }

    @Test
    void testGetTenantDelegatesToTheRepository() {
        Tenant tenant = new Tenant(id: "7", name: "Acme")
        TenantController controller = controllerWith([get: { String id -> id == "7" ? tenant : null }] as Repository)

        assert controller.getTenant("7").is(tenant)
    }

    @Test
    void testSaveTenantAlwaysAssignsAServerMintedGuid() {
        Tenant created = null
        TenantController controller = controllerWith([create: { Tenant tenant ->
            created = tenant
            tenant
        }] as Repository)

        controller.saveTenant(new Tenant(name: "Acme", domain: "acme.com", guid: "attacker-supplied"))

        assert created.guid != "attacker-supplied"
        assert UUID.fromString(created.guid)
    }

    @Test
    void testRemoveTenantDelegatesToTheRepository() {
        Tenant tenant = new Tenant(id: "9")
        TenantController controller = controllerWith([delete: { String id -> id == "9" ? tenant : null }] as Repository)

        assert controller.removeTenant("9").is(tenant)
    }
}
