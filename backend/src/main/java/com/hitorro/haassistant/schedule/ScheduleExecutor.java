package com.hitorro.haassistant.schedule;

import com.hitorro.haassistant.db.Schedule;
import com.hitorro.haassistant.db.ScheduleRepository;
import com.hitorro.haassistant.ha.HaConnectionManager;
import com.hitorro.haassistant.ha.HomeAssistantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Polls the schedule table once per minute and fires any cron expressions whose
 * next firing time is at-or-before "now". Fires by calling the HA service directly —
 * no LLM in the trigger path.
 */
@Component
public class ScheduleExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScheduleExecutor.class);

    private final ScheduleRepository schedules;
    private final HaConnectionManager conns;
    private final Clock clock;
    private final ZoneId zone;

    @Autowired
    public ScheduleExecutor(ScheduleRepository schedules, HaConnectionManager conns) {
        this(schedules, conns, Clock.systemDefaultZone(), ZoneId.systemDefault());
    }

    /** Visible for testing. */
    public ScheduleExecutor(ScheduleRepository schedules, HaConnectionManager conns, Clock clock, ZoneId zone) {
        this.schedules = schedules;
        this.conns = conns;
        this.clock = clock;
        this.zone = zone;
    }

    @Scheduled(cron = "0 * * * * *")
    public void tick() {
        runDue(Instant.now(clock));
    }

    @Transactional
    public void runDue(Instant now) {
        for (Schedule s : schedules.findByEnabledTrue()) {
            try {
                Instant fireAt = mostRecentDueFiring(s, now);
                if (fireAt != null) {
                    fire(s);
                    s.setLastRunAt(fireAt);
                }
            } catch (Exception e) {
                log.warn("Schedule {} failed to evaluate/fire", s.getId(), e);
            }
        }
    }

    /**
     * Returns the most recent cron firing instant that is &le; now and &gt; lastRunAt.
     * Returns null if the schedule isn't due. Coalesces missed firings (e.g. after downtime)
     * into a single fire — a "turn off lights at 11pm" schedule that was missed for two days
     * fires once, not three times.
     *
     * Visible for testing.
     */
    public Instant mostRecentDueFiring(Schedule s, Instant now) {
        Instant since = s.getLastRunAt() != null ? s.getLastRunAt() : s.getCreatedAt();
        CronExpression cron = CronExpression.parse(s.getCronExpr());
        Instant candidate = null;
        ZonedDateTime cursor = cron.next(since.atZone(zone));
        while (cursor != null && !cursor.toInstant().isAfter(now)) {
            candidate = cursor.toInstant();
            cursor = cron.next(cursor);
        }
        return candidate;
    }

    private void fire(Schedule s) {
        HomeAssistantClient client = conns.get(s.getInstanceId()).orElse(null);
        if (client == null) {
            log.warn("Schedule {} target instance {} has no live client — skipping",
                    s.getId(), s.getInstanceId());
            return;
        }
        log.info("Firing schedule {} → {}.{} on {} (instance {})",
                s.getId(), s.getDomain(), s.getService(), s.getEntityIds(),
                client.getInstance().getName());
        client.callService(s.getDomain(), s.getService(), s.getEntityIds(), s.getServiceData());
    }
}
