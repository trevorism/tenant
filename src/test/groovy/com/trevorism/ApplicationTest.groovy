package com.trevorism

import io.micronaut.runtime.EmbeddedApplication
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

class ApplicationTest {

    @Inject
    EmbeddedApplication<?> application

    @Test
    void 'test it works'() {
        assert true
    }

}
