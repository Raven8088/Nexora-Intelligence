package com.nexora.intelligence.infrastructure.semantic;

import com.nexora.intelligence.infrastructure.serviceclient.BoundedHttp;

import java.time.Duration;

/**
 * The bounded HTTP client used for Semantic and its token endpoint. Since INT-3.5 the implementation is shared with
 * the Reports adapter ({@link BoundedHttp}); this type keeps the Semantic adapter's existing wiring unchanged.
 */
class SemanticHttp extends BoundedHttp {

    SemanticHttp(Duration connectTimeout, Duration readTimeout, int maxBytes) {
        super(connectTimeout, readTimeout, maxBytes);
    }
}
