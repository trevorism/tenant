Feature: Self service tenant provisioning guards
  A tenant may only be provisioned behind an active subscription, and only for
  a domain that is well formed, unclaimed and not the platform's own. None of
  these scenarios pay for anything; the guards refuse before any tenant exists.

  Scenario: Requesting a tenant requires authentication
    Given the tenant application is alive
    When an anonymous caller requests a tenant
    Then the tenant request is rejected with status 401

  Scenario: The platform domain cannot be claimed
    Given the tenant application is alive
    When an authenticated caller requests a tenant on the platform domain
    Then the tenant request is rejected with status 400

  Scenario: A malformed domain is refused
    Given the tenant application is alive
    When an authenticated caller requests a tenant with a malformed domain
    Then the tenant request is rejected with status 400

  Scenario: A domain already claimed by another tenant is refused
    Given the tenant application is alive
    When an authenticated caller requests a tenant on an already registered domain
    Then the tenant request is rejected with status 400

  Scenario: Provisioning somebody else's request is refused
    Given the tenant application is alive
    When an authenticated caller provisions a request that does not exist
    Then the tenant request is rejected with status 400

  Scenario: A tenant cannot be provisioned without an active subscription
    Given the tenant application is alive
    And the acceptance caller has an open tenant request
    When the caller provisions the request without paying
    Then the tenant request is rejected with status 400
    And no tenant exists for the acceptance domain

  Scenario: The entitlement sweep is not reachable anonymously
    Given the tenant application is alive
    When an anonymous caller triggers the entitlement sweep
    Then the tenant request is rejected with status 401
