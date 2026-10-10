package com.pab.ficc.ibp.modelgate.server.common;

import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.HttpMethod;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationPathsTest {
    private final ApplicationPaths paths = new ApplicationPaths("/modelgate");

    @Test void logicalUriPreservesRawEncodingAndQuery() {
        var request = MockServerHttpRequest.method(HttpMethod.GET, URI.create("http://localhost/modelgate/svc/crm/a%2Fb?q=a%2Fb&q=2"))
            .contextPath("/modelgate").build();
        assertEquals("/svc/crm/a%2Fb", ApplicationPaths.uri(request).getRawPath());
        assertEquals("q=a%2Fb&q=2", ApplicationPaths.uri(request).getRawQuery());
        assertEquals("/svc/crm/a/b", ApplicationPaths.path(request));
        assertEquals(TrafficType.SERVICE, TrafficType.resolve(MockServerWebExchange.from(request)));
    }

    @Test void noFrameworkContextDoesNotGuessOrStripAPrefix() {
        var request = MockServerHttpRequest.get("/modelgate/v1/chat/completions").build();
        assertEquals(request.getURI(), ApplicationPaths.uri(request));
        assertEquals(TrafficType.UNKNOWN, TrafficType.resolve(MockServerWebExchange.from(request)));
    }

    @Test void allManagementResourcesAreExplicitAndSegmentBounded() {
        for (String root : UrlConstant.MANAGE_PATHS) {
            assertEquals(TrafficType.INTERNAL, TrafficType.of(root));
            assertEquals(TrafficType.INTERNAL, TrafficType.of(root + "/page"));
            assertEquals(TrafficType.UNKNOWN, TrafficType.of(root + "X/page"));
            var request = MockServerHttpRequest.get("/modelgate" + root + "/page")
                .contextPath("/modelgate").build();
            assertEquals(TrafficType.INTERNAL, TrafficType.resolve(MockServerWebExchange.from(request)));
        }
    }

    @Test void deployedModelServiceAndUnknownRequestsRemainProtected() {
        for (String path : new String[]{"/v1", "/v1;a=b/chat", "/svc", "/svc;x=y/api", "/", "/modelgate/models/page"}) {
            var request = MockServerHttpRequest.get("/modelgate" + path).contextPath("/modelgate").build();
            assertTrue(TrafficType.resolve(MockServerWebExchange.from(request)).isBusiness(), path);
        }
        assertEquals(TrafficType.MODEL, TrafficType.resolve(MockServerWebExchange.from(MockServerHttpRequest.get("/modelgate/v1/chat/completions")
            .contextPath("/modelgate").build())));
    }

    @Test void publicUrlsAndHistoricalReplayPathsUseExactBasePathBoundary() {
        assertEquals("/modelgate/svc/crm", paths.publicPath("/svc/crm"));
        assertEquals("/svc/crm/events", paths.applicationPath("/modelgate/svc/crm/events"));
        assertEquals("/svc/crm/events", paths.applicationPath("/svc/crm/events"));
        assertEquals("/modelgateX/svc/crm", paths.applicationPath("/modelgateX/svc/crm"));
        assertEquals("/", paths.applicationPath("/modelgate"));
        assertNull(paths.applicationPath(null));
        assertEquals("/svc/crm", new ApplicationPaths("/").publicPath("/svc/crm"));
    }
}
