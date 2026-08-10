package com.example.agent.service.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteMcpUrlPolicyTest {

    @Test
    void blocksLocalAndPlainHttpEndpointsByDefault() {
        RemoteMcpUrlPolicy policy = new RemoteMcpUrlPolicy(false, false);

        assertThrows(IllegalArgumentException.class,
                () -> policy.validate("http://127.0.0.1:3000/sse"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validate("https://127.0.0.1:3000/sse"));
    }

    @Test
    void permitsExplicitLocalDevelopmentPolicyAndSplitsFullEndpointUrl() {
        RemoteMcpUrlPolicy policy = new RemoteMcpUrlPolicy(true, true);

        RemoteMcpUrlPolicy.ValidatedEndpoint endpoint =
                policy.validate("http://127.0.0.1:3000/custom/sse?tenant=demo");

        assertEquals("http://127.0.0.1:3000", endpoint.baseUri());
        assertEquals("/custom/sse?tenant=demo", endpoint.endpoint());
    }

    @Test
    void defaultsBareServerOriginToModernMcpEndpoint() {
        RemoteMcpUrlPolicy policy = new RemoteMcpUrlPolicy(true, true);

        RemoteMcpUrlPolicy.ValidatedEndpoint endpoint = policy.validate("http://127.0.0.1:3000");

        assertEquals("http://127.0.0.1:3000/mcp", endpoint.normalizedUrl());
        assertEquals("/mcp", endpoint.endpoint());
    }

    @Test
    void rejectsTokensEmbeddedInTheConnectionUrl() {
        RemoteMcpUrlPolicy policy = new RemoteMcpUrlPolicy(true, true);

        assertThrows(IllegalArgumentException.class,
                () -> policy.validate("http://127.0.0.1:3000/sse?access_token=secret"));
    }
}
