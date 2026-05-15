package com.hitorro.haassistant.grouping;

import com.hitorro.haassistant.db.Grouping;
import com.hitorro.haassistant.db.GroupingAction;
import com.hitorro.haassistant.db.GroupingRepository;
import com.hitorro.haassistant.ha.HomeAssistantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Fires every action in a grouping in sort order. Each action is one HA service call.
 * Failures on individual actions are logged but do not abort the rest of the run —
 * a "Pool mode" grouping with 5 lights and 1 dead bulb should still light the other 4.
 */
@Component
public class GroupingRunner {

    private static final Logger log = LoggerFactory.getLogger(GroupingRunner.class);

    private final GroupingRepository groupings;
    private final HomeAssistantClient ha;

    public GroupingRunner(GroupingRepository groupings, HomeAssistantClient ha) {
        this.groupings = groupings;
        this.ha = ha;
    }

    @Transactional(readOnly = true)
    public RunReport run(Long groupingId) {
        Grouping g = groupings.findById(groupingId)
                .orElseThrow(() -> new NoSuchElementException("Grouping " + groupingId + " not found"));
        return runActions(g);
    }

    @Transactional(readOnly = true)
    public RunReport runByName(String name) {
        Grouping g = groupings.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("Grouping '" + name + "' not found"));
        return runActions(g);
    }

    private RunReport runActions(Grouping g) {
        int fired = 0, failed = 0;
        List<GroupingAction> actions = g.getActions();
        for (GroupingAction a : actions) {
            try {
                ha.callService(a.getDomain(), a.getService(), List.of(a.getEntityId()), a.getServiceData());
                fired++;
            } catch (Exception e) {
                failed++;
                log.warn("Grouping {} action {} ({}.{} on {}) failed",
                        g.getId(), a.getId(), a.getDomain(), a.getService(), a.getEntityId(), e);
            }
        }
        log.info("Ran grouping {} '{}': {} fired, {} failed", g.getId(), g.getName(), fired, failed);
        return new RunReport(g.getId(), g.getName(), fired, failed);
    }

    public record RunReport(Long groupingId, String name, int fired, int failed) {}
}
