package com.pab.ficc.ibp.modelgate.server.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pab.ficc.ibp.modelgate.server.common.ServiceState;
import com.pab.ficc.ibp.modelgate.server.service.AuditService;
import com.pab.ficc.ibp.modelgate.server.service.PayloadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContainerDetectionControllerTest {
    private final ServiceState state = new ServiceState();
    private final AuditService audit = mock(AuditService.class);
    private final PayloadService payload = mock(PayloadService.class);
    private final ContainerDetectionController controller = new ContainerDetectionController(state, audit, payload);
    private final Logger logger = (Logger) LoggerFactory.getLogger(ContainerDetectionController.class);
    private final ListAppender<ILoggingEvent> capture = new ListAppender<>();
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach void captureLogs() {
        previousLevel = logger.getLevel(); logger.setLevel(Level.INFO);
        previousAdditive = logger.isAdditive(); logger.setAdditive(false);
        capture.start(); logger.addAppender(capture);
        ReflectionTestUtils.setField(controller, "availableAlways200", true);
    }
    @AfterEach void releaseLogs() {
        logger.detachAppender(capture); capture.stop(); logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }
    private MockServerWebExchange request(HttpMethod method, String endpoint) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method,
                URI.create("http://localhost/modelgate/actuator/detection/" + endpoint + "?token=hidden-query"))
            .contextPath("/modelgate").remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
            .header("Authorization", "Bearer hidden-key").header("Cookie", "hidden-cookie").build());
    }
    private void response(Mono<ResponseEntity<String>> result, int status, String body) {
        var value = result.block(Duration.ofSeconds(5));
        assertNotNull(value); assertEquals(status, value.getStatusCode().value()); assertEquals(body, value.getBody());
    }
    private List<ILoggingEvent> calls() {
        return capture.list.stream().filter(event -> event.getFormattedMessage().startsWith("[Detection] request ")).toList();
    }

    @Test void runningLogsEveryGetAndPostWithoutLeakingSecrets() {
        for (var method : List.of(HttpMethod.GET, HttpMethod.POST)) {
            response(controller.running(request(method, "running")), 200, "running");
            var event = calls().get(calls().size() - 1);
            assertEquals(Level.INFO, event.getLevel());
            var message = event.getFormattedMessage();
            assertTrue(message.contains("method=" + method));
            assertTrue(message.contains("path=/modelgate/actuator/detection/running"));
            assertTrue(message.contains("sourceIp=127.0.0.1 status=200 state=running ready=false draining=false"));
            assertFalse(message.contains("hidden-")); assertFalse(message.contains("token="));
        }
        assertEquals(2, calls().size()); verifyNoInteractions(audit, payload);
    }
    @Test void livenessRemains200DuringDrain() {
        state.markDraining(); response(controller.running(request(HttpMethod.GET, "running")), 200, "running");
        assertTrue(calls().get(0).getFormattedMessage().contains("draining=true"));
    }
    @Test void strictAvailableLogsStartingReadyAndDrainingStatuses() {
        ReflectionTestUtils.setField(controller, "availableAlways200", false);
        response(controller.available(request(HttpMethod.GET, "available")), 503, "starting");
        state.onReady(); response(controller.available(request(HttpMethod.POST, "available")), 200, "available");
        state.markDraining(); response(controller.available(request(HttpMethod.GET, "available")), 503, "draining");
        assertEquals(3, calls().size());
        assertTrue(calls().get(0).getFormattedMessage().contains("status=503 state=starting"));
        assertTrue(calls().get(1).getFormattedMessage().contains("status=200 state=available ready=true draining=false"));
        assertTrue(calls().get(2).getFormattedMessage().contains("status=503 state=draining"));
        verifyNoInteractions(audit, payload);
    }
    @Test void conservativeAvailableStillReturns200AndLogsRealState() {
        response(controller.available(request(HttpMethod.GET, "available")), 200, "starting");
        state.markDraining(); response(controller.available(request(HttpMethod.POST, "available")), 200, "draining");
        assertTrue(calls().get(0).getFormattedMessage().contains("status=200 state=starting"));
        assertTrue(calls().get(1).getFormattedMessage().contains("status=200 state=draining"));
        assertTrue(calls().stream().allMatch(event -> event.getFormattedMessage().contains("availableAlways200=true")));
    }
    @Test void shutdownLogsBothCallsButFlushesOnlyOnce() {
        response(controller.shutdown(request(HttpMethod.POST, "shutdown")), 200, "draining");
        response(controller.shutdown(request(HttpMethod.GET, "shutdown")), 200, "draining");
        assertEquals(2, calls().size());
        assertTrue(calls().get(0).getFormattedMessage().contains("firstShutdown=true"));
        assertTrue(calls().get(1).getFormattedMessage().contains("firstShutdown=false"));
        verify(audit, times(1)).drainForShutdown(); verify(payload, times(1)).drainForShutdown();
    }
    @Test void shutdownArrivalIsLoggedEvenIfFlushFails() {
        doAnswer(invocation -> {
            assertEquals(1, calls().size()); // 先记录请求到达，再刷盘。
            throw new IllegalStateException("simulated failure");
        }).when(audit).drainForShutdown();
        response(controller.shutdown(request(HttpMethod.POST, "shutdown")), 200, "draining");
        assertEquals(1, calls().size()); assertTrue(state.isDraining());
    }
    @Test void missingRemoteAddressDoesNotFailProbe() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/modelgate/actuator/detection/running")
            .contextPath("/modelgate").build());
        response(controller.running(exchange), 200, "running");
        assertTrue(calls().get(0).getFormattedMessage().contains("sourceIp=-"));
    }
}
