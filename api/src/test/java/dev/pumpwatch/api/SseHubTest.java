package dev.pumpwatch.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pumpwatch.api.Views.FlagView;
import dev.pumpwatch.common.Flag;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseHubTest {
    static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
    static final ApiProperties PROPS =
            new ApiProperties(List.of(), 120, 30, Duration.ofSeconds(15), Duration.ofSeconds(1), 2, 5);

    ReadRepository repo = mock(ReadRepository.class);
    SseHub hub = new SseHub(repo, PROPS);

    @AfterEach
    void closeHub() {
        hub.close();
    }

    @Test
    void springCreatesTheHubWithItsProductionDependencies() {
        new ApplicationContextRunner().withUserConfiguration(SseHub.class)
                .withBean(ReadRepository.class, () -> repo).withBean(ApiProperties.class, () -> PROPS)
                .run(context -> {
                    assertThat(context).hasSingleBean(SseHub.class);
                    context.getBean(SseHub.class).subscribe("live", null, "c");
                    verify(repo).maxFlagId("live");
                });
    }

    static FlagView flag(long id) {
        var e = new Flag.Evidence(70, "rules-1", List.of(), 5, 2, new Flag.CatalystCheck(false, T, T, List.of()));
        return new FlagView(id, "GME", T, e);
    }

    @Test
    void resumesFromLastEventIdAndAdvancesTheCursor() {
        when(repo.flagsAfter("live", 41, SseHub.BATCH)).thenReturn(List.of(flag(42), flag(43)));
        hub.subscribe("live", 41L, "c");
        hub.tick();
        await().untilAsserted(() -> {
            hub.tick();
            verify(repo).flagsAfter("live", 43, SseHub.BATCH);
        });
        var order = inOrder(repo);
        order.verify(repo).flagsAfter("live", 41, SseHub.BATCH);
        order.verify(repo).flagsAfter("live", 43, SseHub.BATCH);
        verify(repo, never()).maxFlagId("live");
    }

    @Test
    void withoutLastEventIdStartsFromNow() {
        when(repo.maxFlagId("live")).thenReturn(10L);
        hub.subscribe("live", null, "c");
        hub.tick();
        verify(repo).flagsAfter("live", 10, SseHub.BATCH);
    }

    @Test
    void streamsAreCapped() {
        hub.subscribe("live", 0L, "c");
        hub.subscribe("live", 0L, "c");
        org.junit.jupiter.api.Assertions.assertThrows(SseHub.TooManyStreams.class, () -> hub.subscribe("live", 0L, "c"));
        assertThat(hub.clientCount()).isEqualTo(2);
    }

    @Test
    void oneClientCannotTakeEverySlot() {
        var wide = new SseHub(repo, new ApiProperties(List.of(), 120, 30, Duration.ofSeconds(15), Duration.ofSeconds(1), 100, 5));
        for (int i = 0; i < 5; i++) wide.subscribe("live", 0L, "greedy");
        org.junit.jupiter.api.Assertions.assertThrows(SseHub.TooManyStreams.class, () -> wide.subscribe("live", 0L, "greedy"));
        wide.subscribe("live", 0L, "other"); // another client still gets in
        assertThat(wide.clientCount()).isEqualTo(5 + 1);
    }

    @Test
    void heartbeatIsSentWhenIdle() {
        var clock = new java.time.Clock() {
            Instant now = T;
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now; }
        };
        try (var timed = new SseHub(repo, PROPS, clock); var emitters = mockConstruction(SseEmitter.class)) {
            var emitter = timed.subscribe("live", 0L, "c");
            clock.now = T.plusSeconds(16);
            timed.tick();
            await().untilAsserted(() -> verify(emitter).send(any(SseEmitter.SseEventBuilder.class)));
        }
    }

    @Test
    void viewersOfOneRunShareOneQueryPerTick() {
        var wide = new SseHub(repo, new ApiProperties(List.of(), 120, 30, Duration.ofSeconds(15), Duration.ofSeconds(1), 100, 50));
        for (int i = 0; i < 20; i++) wide.subscribe("live", 5L, "c");
        wide.tick();
        verify(repo, org.mockito.Mockito.times(1)).flagsAfter("live", 5, SseHub.BATCH);
    }

    @Test
    void aViewerFarBehindDoesNotDelayACaughtUpViewer() {
        when(repo.flagsAfter("live", 0, SseHub.BATCH)).thenReturn(List.of(flag(1)));
        when(repo.flagsAfter("live", 50, SseHub.BATCH)).thenReturn(List.of(flag(51)));
        hub.subscribe("live", 0L, "laggard");
        hub.subscribe("live", 50L, "current");
        hub.tick();
        verify(repo).flagsAfter("live", 0, SseHub.BATCH);
        verify(repo).flagsAfter("live", 50, SseHub.BATCH);
        await().untilAsserted(() -> {
            hub.tick();
            verify(repo, atLeastOnce()).flagsAfter("live", 51, SseHub.BATCH);
            verify(repo, atLeastOnce()).flagsAfter("live", 1, SseHub.BATCH);
        });
    }

    @Test
    void differentRunsGetOneQueryEach() {
        hub.subscribe("live", 0L, "a");
        hub.subscribe("r2", 0L, "b");
        hub.tick();
        verify(repo).flagsAfter("live", 0, SseHub.BATCH);
        verify(repo).flagsAfter("r2", 0, SseHub.BATCH);
    }

    @Test
    void blockedWriterDoesNotDelayAnotherViewerAtTheSameCursor() throws Exception {
        when(repo.flagsAfter("live", 0, SseHub.BATCH)).thenReturn(List.of(flag(1)));
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new CountDownLatch(1);
        try (var emitters = mockConstruction(SseEmitter.class, (emitter, context) -> {
            doAnswer(call -> {
                if (context.getCount() == 1) {
                    blocked.countDown();
                    assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
                } else {
                    delivered.countDown();
                }
                return null;
            }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        }); var poller = Executors.newSingleThreadExecutor()) {
            var slow = hub.subscribe("live", 0L, "slow");
            hub.subscribe("live", 0L, "current");
            var poll = poller.submit(hub::tick);
            try {
                assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(delivered.await(1, TimeUnit.SECONDS)).as("healthy viewer receives its flag").isTrue();
                poll.get(1, TimeUnit.SECONDS);
                hub.tick();
                verify(slow, org.mockito.Mockito.after(100).times(1)).send(any(SseEmitter.SseEventBuilder.class));
            } finally {
                release.countDown();
            }
        }
    }
}
