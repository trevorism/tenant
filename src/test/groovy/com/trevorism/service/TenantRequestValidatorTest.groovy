package com.trevorism.service

import com.trevorism.model.Tenant
import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import com.trevorism.model.TenantRequestStatus
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertThrows

class TenantRequestValidatorTest {

    private TenantRequestValidator validator = new TenantRequestValidator()

    @Test
    void testAcceptsAWellFormedRequest() {
        validator.validate(new TenantRequestInput(name: "Acme", domain: "acme.com"), "user-1", [], [])
    }

    @Test
    void testNormalizesDomainCaseAndWhitespace() {
        assert TenantRequestValidator.normalizeDomain("  ACME.com ") == "acme.com"
        assert TenantRequestValidator.normalizeName("  Acme ") == "Acme"
    }

    @Test
    void testRejectsAnUnidentifiedCaller() {
        assertThrows(TenantRequestException) {
            validator.validate(new TenantRequestInput(name: "Acme", domain: "acme.com"), null, [], [])
        }
    }

    @Test
    void testRejectsShortNames() {
        assertThrows(TenantRequestException) {
            validator.validate(new TenantRequestInput(name: "ab", domain: "acme.com"), "user-1", [], [])
        }
    }

    @Test
    void testRejectsMissingName() {
        assertThrows(TenantRequestException) {
            validator.validate(new TenantRequestInput(domain: "acme.com"), "user-1", [], [])
        }
    }

    @Test
    void testRejectsMalformedDomains() {
        ["not a domain", "acme", "-acme.com", "acme-.com", "acme..com", "http://acme.com", ""].each { candidate ->
            assertThrows(TenantRequestException, {
                validator.validate(new TenantRequestInput(name: "Acme", domain: candidate), "user-1", [], [])
            }, "expected ${candidate} to be rejected")
        }
    }

    @Test
    void testRejectsThePlatformDomainAndItsSubdomains() {
        ["trevorism.com", "login.trevorism.com", "anything.trevorism.com"].each { candidate ->
            assertThrows(TenantRequestException, {
                validator.validate(new TenantRequestInput(name: "Acme", domain: candidate), "user-1", [], [])
            }, "expected ${candidate} to be reserved")
        }
    }

    @Test
    void testRejectsADomainAlreadyRegistered() {
        List<Tenant> tenants = [new Tenant(name: "Other", domain: "ACME.com")]
        assertThrows(TenantRequestException) {
            validator.validate(new TenantRequestInput(name: "Acme", domain: "acme.com"), "user-1", tenants, [])
        }
    }

    @Test
    void testRejectsANameAlreadyRegistered() {
        List<Tenant> tenants = [new Tenant(name: "acme", domain: "other.com")]
        assertThrows(TenantRequestException) {
            validator.validate(new TenantRequestInput(name: "Acme", domain: "acme.com"), "user-1", tenants, [])
        }
    }

    @Test
    void testRejectsASecondOpenRequestFromTheSameOwner() {
        List<TenantRequest> owned = [new TenantRequest(ownerUserId: "user-1", status: TenantRequestStatus.PENDING_PAYMENT)]
        assertThrows(TenantRequestException) {
            validator.validate(new TenantRequestInput(name: "Acme", domain: "acme.com"), "user-1", [], owned)
        }
    }

    @Test
    void testAllowsANewRequestWhenThePreviousOneIsSuspended() {
        List<TenantRequest> owned = [new TenantRequest(ownerUserId: "user-1", status: TenantRequestStatus.SUSPENDED)]
        validator.validate(new TenantRequestInput(name: "Acme", domain: "acme.com"), "user-1", [], owned)
    }

    @Test
    void testRejectsADomainAnotherOwnerHasAlreadyReserved() {
        List<TenantRequest> requests = [new TenantRequest(ownerUserId: "user-2", name: "Rival",
                domain: "acme.com", status: TenantRequestStatus.PENDING_PAYMENT)]
        assertThrows(TenantRequestException) {
            validator.validate(new TenantRequestInput(name: "Acme", domain: "acme.com"), "user-1", [], requests)
        }
    }

    @Test
    void testRejectsANameAnotherOwnerHasAlreadyReserved() {
        List<TenantRequest> requests = [new TenantRequest(ownerUserId: "user-2", name: "acme",
                domain: "other.com", status: TenantRequestStatus.PENDING_PAYMENT)]
        assertThrows(TenantRequestException) {
            validator.validate(new TenantRequestInput(name: "Acme", domain: "acme.com"), "user-1", [], requests)
        }
    }

    @Test
    void testASuspendedRequestFromAnotherOwnerDoesNotReserveTheDomain() {
        List<TenantRequest> requests = [new TenantRequest(ownerUserId: "user-2", name: "Rival",
                domain: "acme.com", status: TenantRequestStatus.SUSPENDED)]
        validator.validate(new TenantRequestInput(name: "Acme", domain: "acme.com"), "user-1", [], requests)
    }
}
