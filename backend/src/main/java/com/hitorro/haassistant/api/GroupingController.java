package com.hitorro.haassistant.api;

import com.hitorro.haassistant.db.Grouping;
import com.hitorro.haassistant.db.GroupingAction;
import com.hitorro.haassistant.db.GroupingRepository;
import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.db.HaInstanceRepository;
import com.hitorro.haassistant.grouping.GroupingRunner;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/groupings")
public class GroupingController {

    private final GroupingRepository repo;
    private final GroupingRunner runner;
    private final HaInstanceRepository instances;

    public GroupingController(GroupingRepository repo, GroupingRunner runner, HaInstanceRepository instances) {
        this.repo = repo;
        this.runner = runner;
        this.instances = instances;
    }

    public record ActionDto(
            @NotBlank String entityId,
            @NotBlank String domain,
            @NotBlank String service,
            Map<String, Object> serviceData) {}

    public record CreateRequest(
            @NotBlank String name,
            String description,
            String instance,
            List<ActionDto> actions) {}

    @GetMapping
    public List<Grouping> list() { return repo.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<Grouping> get(@PathVariable Long id) {
        return repo.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public Grouping create(@RequestBody CreateRequest req) {
        Long instanceId = resolveInstanceId(req.instance());
        Grouping g = new Grouping(instanceId, req.name(), req.description());
        if (req.actions() != null) {
            for (ActionDto a : req.actions()) {
                g.addAction(new GroupingAction(a.entityId(), a.domain(), a.service(), a.serviceData()));
            }
        }
        return repo.save(g);
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<GroupingRunner.RunReport> run(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(runner.run(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
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
