package com.hitorro.haassistant.ha;

import com.hitorro.haassistant.config.AppProperties;
import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.db.HaInstanceRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * On first boot after V3, the placeholder 'default' instance row exists but
 * has no URL or token. If the user has set HA_URL / HA_TOKEN in local.env,
 * fold those into the default row so existing single-instance setups keep
 * working without manual UI config. Once the user manages instances via the
 * UI they can rename/delete this row freely.
 *
 * Runs BEFORE HaConnectionManager.startAll() because @PostConstruct fires at
 * bean creation time and HaConnectionManager kicks off on ApplicationReadyEvent.
 */
@Component
public class DefaultInstanceBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DefaultInstanceBootstrap.class);

    private final AppProperties props;
    private final HaInstanceRepository repo;

    public DefaultInstanceBootstrap(AppProperties props, HaInstanceRepository repo) {
        this.props = props;
        this.repo = repo;
    }

    @PostConstruct
    public void run() {
        if (props.ha() == null) return;
        String envUrl = props.ha().url();
        String envToken = props.ha().token();
        if (envToken == null || envToken.isBlank()) return;

        HaInstance def = repo.findFirstByIsDefaultTrue().orElse(null);
        if (def == null) return; // V3 migration didn't run yet (shouldn't happen)
        if (def.getToken() != null && !def.getToken().isBlank()) {
            log.debug("[{}] already configured, leaving env values alone", def.getName());
            return;
        }
        log.info("[{}] backfilling URL+token from env (HA_URL, HA_TOKEN)", def.getName());
        if (envUrl != null && !envUrl.isBlank()) def.setInternalUrl(envUrl);
        def.setToken(envToken);
        repo.save(def);
    }
}
