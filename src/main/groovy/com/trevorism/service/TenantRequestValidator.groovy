package com.trevorism.service

import com.trevorism.model.Tenant
import com.trevorism.model.TenantRequest
import com.trevorism.model.TenantRequestInput
import com.trevorism.model.TenantRequestStatus

class TenantRequestValidator {

    static final int MINIMUM_NAME_LENGTH = 3
    static final int MAXIMUM_NAME_LENGTH = 64
    static final int MAXIMUM_DOMAIN_LENGTH = 253
    static final List<String> RESERVED_DOMAIN_SUFFIXES = ["trevorism.com"]

    private static final java.util.regex.Pattern DOMAIN_PATTERN =
            ~/^(?!-)[a-z0-9-]{1,63}(?<!-)(\.(?!-)[a-z0-9-]{1,63}(?<!-))+$/

    static String normalizeName(String name) {
        return name?.trim()
    }

    static String normalizeDomain(String domain) {
        return domain?.trim()?.toLowerCase()
    }

    void validate(TenantRequestInput input, String ownerUserId, List<Tenant> tenants, List<TenantRequest> requests) {
        if (!ownerUserId) {
            throw new TenantRequestException("Unable to identify the requesting user")
        }

        String name = normalizeName(input?.name)
        String domain = normalizeDomain(input?.domain)

        validateShape(name, domain)
        validateOwnerHasNoOpenRequest(requests.findAll { it.ownerUserId == ownerUserId })
        validateAvailability(name, domain, tenants, openRequests(requests))
    }

    void validateShape(String name, String domain) {
        validateName(name)
        validateDomain(domain)
    }

    static List<TenantRequest> openRequests(List<TenantRequest> requests) {
        return requests.findAll { it.status != TenantRequestStatus.SUSPENDED }
    }

    private static void validateName(String name) {
        if (!name || name.length() < MINIMUM_NAME_LENGTH || name.length() > MAXIMUM_NAME_LENGTH) {
            throw new TenantRequestException("Tenant name must be between ${MINIMUM_NAME_LENGTH} and ${MAXIMUM_NAME_LENGTH} characters")
        }
    }

    private static void validateDomain(String domain) {
        if (!domain || domain.length() > MAXIMUM_DOMAIN_LENGTH || !DOMAIN_PATTERN.matcher(domain).matches()) {
            throw new TenantRequestException("A valid tenant domain is required")
        }
        if (RESERVED_DOMAIN_SUFFIXES.any { domain == it || domain.endsWith(".${it}") }) {
            throw new TenantRequestException("Domain ${domain} is reserved")
        }
    }

    static void validateAvailability(String name, String domain, List<Tenant> tenants,
                                     List<TenantRequest> reservations = []) {
        if (tenants.any { normalizeDomain(it.domain) == domain }) {
            throw new TenantRequestException("Domain ${domain} is already in use")
        }
        if (tenants.any { it.name?.equalsIgnoreCase(name) }) {
            throw new TenantRequestException("Tenant name ${name} is already in use")
        }
        if (reservations.any { normalizeDomain(it.domain) == domain }) {
            throw new TenantRequestException("Domain ${domain} is already reserved by another request")
        }
        if (reservations.any { it.name?.equalsIgnoreCase(name) }) {
            throw new TenantRequestException("Tenant name ${name} is already reserved by another request")
        }
    }

    private static void validateOwnerHasNoOpenRequest(List<TenantRequest> ownerRequests) {
        if (ownerRequests.any { it.status != TenantRequestStatus.SUSPENDED }) {
            throw new TenantRequestException("An existing tenant request is already open for this user")
        }
    }
}
