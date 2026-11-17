package dev.pumpwatch.api;

import dev.pumpwatch.api.Views.Envelope;
import dev.pumpwatch.api.Views.FlagView;
import dev.pumpwatch.common.Json;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Flag stream. Every client has a cursor (flag id); a poll reads newer flags from Postgres, so resume after a drop
 * is the same query as normal delivery and nothing here has to survive a restart.
 */
@Component
public class SseHub implements AutoCloseable {
    static final int BATCH = 100;
    private record Poll(String runId, long cursor) {}

    private static final class Client {
        final SseEmitter emitter;
        final String runId;
        final String owner;
        final AtomicBoolean writing = new AtomicBoolean();
        volatile boolean active = true;
        long cursor;
        Instant lastSent;

        Client(SseEmitter emitter, String runId, String owner, long cursor, Instant now) {
            this.emitter = emitter;
            this.owner = owner;
            this.runId = runId;
            this.cursor = cursor;
            this.lastSent = now;
        }
    }

    public static class TooManyStreams extends RuntimeException {}

    private final ReadRepository repo;
    private final ApiProperties props;
    private final Clock clock;
    private final ThreadPoolExecutor writers;
    private final List<Client> clients = new CopyOnWriteArrayList<>();

    @Autowired
    public SseHub(ReadRepository repo, ApiProperties props) {
        this(repo, props, Clock.systemUTC());
    }

    SseHub(ReadRepository repo, ApiProperties props, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.clock = clock;
        writers = new ThreadPoolExecutor(0, Math.max(1, props.maxStreams()), 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofVirtual().name("sse-writer-", 0).factory());
    }

    /** lastEventId null means "from now". owner is the rate-limit client key, so one client cannot fill every slot. */
    public SseEmitter subscribe(String runId, Long lastEventId, String owner) {
        if (full(owner)) throw new TooManyStreams();
        var emitter = new SseEmitter(30 * 60 * 1000L); // browsers reconnect with Last-Event-ID
        long cursor = lastEventId != null ? lastEventId : repo.maxFlagId(runId);
        var client = new Client(emitter, runId, owner, cursor, clock.instant());
        synchronized (clients) {
            if (writers.isShutdown() || full(owner)) throw new TooManyStreams();
            clients.add(client);
        }
        Runnable drop = () -> drop(client);
        emitter.onCompletion(drop);
        emitter.onTimeout(drop);
        emitter.onError(e -> drop.run());
        return emitter;
    }

    private boolean full(String owner) {
        return clients.size() >= props.maxStreams()
                || clients.stream().filter(c -> c.owner.equals(owner)).count() >= props.maxStreamsPerClient();
    }

    int clientCount() {
        return clients.size();
    }

    @Scheduled(fixedDelayString = "${api.poll-interval:PT1S}")
    public void tick() {
        var groups = clients.stream()
                .filter(client -> client.active && client.writing.compareAndSet(false, true))
                .collect(Collectors.groupingBy(client -> new Poll(client.runId, client.cursor)));
        for (var group : groups.entrySet()) {
            Poll poll = group.getKey();
            List<FlagView> flags;
            try {
                flags = repo.flagsAfter(poll.runId(), poll.cursor(), BATCH);
            } catch (RuntimeException e) {
                for (Client client : group.getValue()) {
                    fail(client, e);
                    finish(client);
                }
                continue;
            }
            for (Client client : group.getValue()) submit(client, flags);
        }
    }

    private void submit(Client client, List<FlagView> flags) {
        boolean submitted = false;
        try {
            if (!client.active || (flags.isEmpty() && !client.lastSent.plus(props.heartbeat()).isBefore(clock.instant())))
                return;
            writers.execute(() -> deliver(client, flags));
            submitted = true;
        } catch (RejectedExecutionException ignored) {
        } finally {
            if (!submitted) finish(client);
        }
    }

    private void deliver(Client client, List<FlagView> flags) {
        try {
            for (FlagView flag : flags) {
                if (!client.active) return;
                client.emitter.send(SseEmitter.event().id(Long.toString(flag.id())).name("flag")
                        .data(Json.toJson(Envelope.of(flag)), MediaType.APPLICATION_JSON));
                client.cursor = flag.id();
                client.lastSent = clock.instant();
            }
            if (flags.isEmpty() && client.active) {
                client.emitter.send(SseEmitter.event().comment("heartbeat"));
                client.lastSent = clock.instant();
            }
        } catch (Exception e) {
            fail(client, e);
        } finally {
            finish(client);
        }
    }

    private void drop(Client client) {
        client.active = false;
        clients.remove(client);
    }

    private void fail(Client client, Exception error) {
        drop(client);
        try {
            client.emitter.completeWithError(error);
        } catch (RuntimeException ignored) {
        }
    }

    private void finish(Client client) {
        client.writing.set(false);
        if (writers.isShutdown() && !client.active) complete(client);
    }

    private void complete(Client client) {
        try {
            client.emitter.complete();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    @PreDestroy
    public void close() {
        List<Client> open;
        synchronized (clients) {
            open = List.copyOf(clients);
            open.forEach(client -> client.active = false);
            clients.clear();
            writers.shutdownNow();
        }
        for (Client client : open) {
            if (!client.writing.get()) complete(client);
        }
    }
}
