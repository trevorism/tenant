package com.trevorism.gcloud

/**
 * @author tbrooks
 */

this.metaClass.mixin(io.cucumber.groovy.Hooks)
this.metaClass.mixin(io.cucumber.groovy.EN)

String baseUrl = System.getenv("ACCEPTANCE_BASE_URL") ?: "https://tenant.auth.trevorism.com"

def contextRootContent
def pingContent

Given(~/^the application is alive$/) { ->
    try {
        new URL("${baseUrl}/ping").text
    }
    catch (Exception ignored) {
        Thread.sleep(10000)
        new URL("${baseUrl}/ping").text
    }
}

When(~/^I navigate to the application root$/) { ->
    contextRootContent = new URL("${baseUrl}").text
}

Then(~/^a link to the help page is displayed$/) { ->
    assert contextRootContent
    assert contextRootContent.contains("/help")
}

When(~/^I ping the application$/) { ->
    pingContent = new URL("${baseUrl}/ping").text
}

Then(~/^pong is returned, to indicate the service is alive$/) { ->
    assert pingContent == "pong"
}
