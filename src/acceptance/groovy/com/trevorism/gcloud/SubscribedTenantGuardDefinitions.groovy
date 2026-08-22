package com.trevorism.gcloud

import com.google.gson.Gson
import com.trevorism.http.HttpClient
import com.trevorism.http.JsonHttpClient
import com.trevorism.http.util.InvalidRequestException
import com.trevorism.https.AppClientSecureHttpClient
import com.trevorism.https.SecureHttpClient
import org.apache.hc.client5.http.HttpResponseException

this.metaClass.mixin(io.cucumber.groovy.Hooks)
this.metaClass.mixin(io.cucumber.groovy.EN)

Gson gson = new Gson()
HttpClient httpClient = new JsonHttpClient()
SecureHttpClient appClient = new AppClientSecureHttpClient()
String guardBaseUrl = System.getenv("ACCEPTANCE_BASE_URL") ?: "https://tenant.auth.trevorism.com"

String acceptanceDomain = "zz-acceptance-provisioning.test"
String acceptanceName = "zz_acceptance_provisioning"
String openRequestId
int lastStatus

Closure<Integer> statusOf = { Closure call ->
    try {
        call.call()
        return 200
    } catch (InvalidRequestException e) {
        return e.statusCode
    } catch (HttpResponseException e) {
        return e.statusCode
    } catch (Exception ignored) {
        return -1
    }
}

Closure<String> requestBody = { String name, String domain ->
    return gson.toJson([name: name, domain: domain])
}

Given(/the tenant application is alive/) { ->
    try {
        new URL("${guardBaseUrl}/ping").text
    } catch (Exception ignored) {
        Thread.sleep(10000)
        new URL("${guardBaseUrl}/ping").text
    }
}

Given(/the acceptance caller has an open tenant request/) { ->
    String json = appClient.post("${guardBaseUrl}/subscribedtenant/", requestBody(acceptanceName, acceptanceDomain))
    openRequestId = gson.fromJson(json, Map).id
    assert openRequestId
}

When(/an anonymous caller requests a tenant/) { ->
    lastStatus = statusOf { httpClient.post("${guardBaseUrl}/subscribedtenant/", requestBody("zz_anonymous", "zz-anonymous.test")) }
}

When(/an authenticated caller requests a tenant on the platform domain/) { ->
    lastStatus = statusOf { appClient.post("${guardBaseUrl}/subscribedtenant/", requestBody("zz_reserved", "trevorism.com")) }
}

When(/an authenticated caller requests a tenant with a malformed domain/) { ->
    lastStatus = statusOf { appClient.post("${guardBaseUrl}/subscribedtenant/", requestBody("zz_malformed", "not a domain")) }
}

When(/an authenticated caller requests a tenant on an already registered domain/) { ->
    lastStatus = statusOf { appClient.post("${guardBaseUrl}/subscribedtenant/", requestBody("zz_duplicate", "memowand.com")) }
}

When(/an authenticated caller provisions a request that does not exist/) { ->
    lastStatus = statusOf { appClient.post("${guardBaseUrl}/subscribedtenant/zz-no-such-request/provision", "{}") }
}

When(/the caller provisions the request without paying/) { ->
    lastStatus = statusOf { appClient.post("${guardBaseUrl}/subscribedtenant/${openRequestId}/provision", "{}") }
}

When(/an anonymous caller triggers the entitlement sweep/) { ->
    lastStatus = statusOf { httpClient.post("${guardBaseUrl}/subscribedtenant/sweep", "{}") }
}

Then(/the tenant request is rejected with status {int}/) { Integer expected ->
    assert lastStatus == expected
}

Then(/no tenant exists for the acceptance domain/) { ->
    String json = appClient.get("${guardBaseUrl}/tenant/")
    List tenants = gson.fromJson(json, List)
    assert !tenants.any { it.domain == acceptanceDomain }
}

After { ->
    if (openRequestId) {
        try {
            appClient.delete("${guardBaseUrl}/subscribedtenant/${openRequestId}")
        } catch (Exception ignored) {
        }
        openRequestId = null
    }
}
