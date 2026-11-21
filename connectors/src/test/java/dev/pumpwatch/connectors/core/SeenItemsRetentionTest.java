package dev.pumpwatch.connectors.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class SeenItemsRetentionTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void blueskyRowsOutliveReplayRecordingsWhileOtherSourcesExpireAfterSevenDays() {
        var jdbc = TestDb.migrated(PG);
        jdbc.update("DELETE FROM seen_items");
        insert(jdbc, "bluesky", "recent-post", 10);
        insert(jdbc, "bluesky", "old-post", 40);
        insert(jdbc, "alpaca", "old-trade", 10);
        insert(jdbc, "alpaca", "new-trade", 2);

        new SeenItems(jdbc).purgeOld();

        var seen = new SeenItems(jdbc);
        assertThat(seen.isSeen("bluesky", "recent-post")).isTrue();
        assertThat(seen.isSeen("bluesky", "old-post")).isFalse();
        assertThat(seen.isSeen("alpaca", "old-trade")).isFalse();
        assertThat(seen.isSeen("alpaca", "new-trade")).isTrue();
    }

    private static void insert(JdbcTemplate jdbc, String source, String itemId, int daysAgo) {
        jdbc.update("INSERT INTO seen_items (source, item_id, first_seen) VALUES (?, ?, now() - make_interval(days => ?))",
                source, itemId, daysAgo);
    }
}
