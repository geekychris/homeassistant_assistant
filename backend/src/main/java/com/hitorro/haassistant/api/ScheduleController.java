package com.hitorro.haassistant.api;

import com.hitorro.haassistant.db.Schedule;
import com.hitorro.haassistant.db.ScheduleRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleRepository repo;

    public ScheduleController(ScheduleRepository repo) {
        this.repo = repo;
    }

    public record CreateRequest(
            @NotBlank String name,
            @NotBlank String cronExpr,
            @NotBlank String domain,
            @NotBlank String service,
            List<String> entityIds,
            Map<String, Object> serviceData) {}

    @GetMapping
    public List<Schedule> list() { return repo.findAll(); }

    @PostMapping
    public Schedule create(@RequestBody CreateRequest req) {
        return repo.save(new Schedule(
                req.name(), req.cronExpr(), req.domain(), req.service(),
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
}
