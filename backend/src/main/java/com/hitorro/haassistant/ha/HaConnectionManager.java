package com.hitorro.haassistant.ha;

import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.db.HaInstanceRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one {@link HomeAssistantClient} per enabled HaInstance. Other components
 * inject HaConnectionManager and look up the right client by instance id or by
 * "default".
 *
 * Lifecycle: on ApplicationReady, start a client for every enabled instance.
 * When an instance is added/edited/disabled at runtime, restart() picks it up.
 */
@Component
public class HaConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(HaConnectionManager.class);

    private final HaInstanceRepository instances;
    private final ConcurrentHashMap<Long, HomeAssistantClient> clients = new ConcurrentHashMap<>();
    private final List<ClientLifecycleListener> listeners;

    public HaConnectionManager(HaInstanceRepository instances, List<ClientLifecycleListener> listeners) {
        this.instances = instances;
        this.listeners = listeners;
    }

    /** Components that need to react when a new client comes online (e.g. RegistrySync). */
    public interface ClientLifecycleListener {
        void onClientReady(HomeAssistantClient client);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        for (HaInstance i : instances.findByEnabledTrue()) {
            startInstance(i);
        }
    }

    @PreDestroy
    public void stopAll() {
        clients.values().forEach(HomeAssistantClient::close);
        clients.clear();
    }

    public synchronized void startInstance(HaInstance i) {
        if (!i.isConfigured()) {
            log.warn("[{}] not configured (token + URL required) — not starting", i.getName());
            return;
        }
        HomeAssistantClient existing = clients.remove(i.getId());
        if (existing != null) existing.close();

        HomeAssistantClient client = new HomeAssistantClient(i);
        for (ClientLifecycleListener l : listeners) {
            client.onAuth(() -> l.onClientReady(client));
        }
        clients.put(i.getId(), client);
        client.connect();
    }

    public synchronized void stopInstance(Long instanceId) {
        HomeAssistantClient c = clients.remove(instanceId);
        if (c != null) c.close();
    }

    public synchronized void restartInstance(Long instanceId) {
        HaInstance i = instances.findById(instanceId)
                .orElseThrow(() -> new NoSuchElementException("instance " + instanceId));
        if (i.isEnabled()) startInstance(i);
        else stopInstance(instanceId);
    }

    public Optional<HomeAssistantClient> get(Long instanceId) {
        return Optional.ofNullable(clients.get(instanceId));
    }

    public Optional<HomeAssistantClient> getDefault() {
        return instances.findFirstByIsDefaultTrue().flatMap(i -> get(i.getId()));
    }

    /** Resolve "belmont", "orlando", or null/empty (→ default). Throws if missing. */
    public HomeAssistantClient resolve(String nameOrNull) {
        if (nameOrNull == null || nameOrNull.isBlank()) {
            return getDefault().orElseThrow(() ->
                    new NoSuchElementException("no default HA instance configured"));
        }
        HaInstance i = instances.findByName(nameOrNull)
                .orElseThrow(() -> new NoSuchElementException("HA instance '" + nameOrNull + "' not found"));
        return get(i.getId())
                .orElseThrow(() -> new NoSuchElementException("HA instance '" + nameOrNull + "' is not connected"));
    }

    public Collection<HomeAssistantClient> allClients() { return clients.values(); }
}
