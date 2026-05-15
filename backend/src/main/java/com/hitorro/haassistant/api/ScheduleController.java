package com.hitorro.haassistant.api;

import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.db.HaInstanceRepository;
import com.hitorro.haassistant.db.Schedule;
import com.hitorro.haassistant.db.ScheduleRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleRepository repo;
    private final HaInstanceRepository instances;

    public ScheduleController(ScheduleRepository repo, HaInstanceRepository instances) {
        this.repo = repo;
        this.instances = instances;
    }

    public record CreateRequest(
            @NotBlank String name,
            @NotBlank String cronExpr,
            @NotBlank String domain,
            @NotBlank String service,
            String instance,
            List<String> entityIds,
            Map<String, Object> serviceData) {}

    @GetMapping
    public List<Schedule> list() { return repo.findAll(); }

    @PostMapping
    public Schedule create(@RequestBody CreateRequest req) {
        Long instanceId = resolveInstanceId(req.instance());
        return repo.save(new Schedule(
                instanceId, req.name(), req.cronExpr(), req.domain(), req.service(),
                req.entityIds(), req.serviceData()));
    }

    @PutMapping("/{id}/enabled")
    public ResponseEntity<Schedule> setEnabled(@PathVariable Long id, @RequestParam boolean value) {
        return repo.findById(id)
                .map(s -> { s.setEnabled(value); return ResponseEntity.ok(repo.save(s)); })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private Long resolveInstanceId(String name) {
        HaInstance i = (name == null || name.isBlank())
                ? instances.findFirstByIsDefaultTrue()
                        .orElseThrow(() -> new NoSuchElementException("no default instance"))
                : instances.findByName(name)
                        .orElseThrow(() -> new NoSuchElementException("instance " + name));
        return i.getId();
    }
}
