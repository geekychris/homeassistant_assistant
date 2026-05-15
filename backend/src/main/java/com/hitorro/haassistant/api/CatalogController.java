package com.hitorro.haassistant.api;

import com.hitorro.haassistant.db.Area;
import com.hitorro.haassistant.db.AreaRepository;
import com.hitorro.haassistant.db.HaEntity;
import com.hitorro.haassistant.db.HaEntityRepository;
import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.db.HaInstanceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final AreaRepository areas;
    private final HaEntityRepository entities;
    private final HaInstanceRepository instances;

    public CatalogController(AreaRepository areas, HaEntityRepository entities, HaInstanceRepository instances) {
        this.areas = areas;
        this.entities = entities;
        this.instances = instances;
    }

    @GetMapping("/areas")
    public ResponseEntity<List<Area>> listAreas(@RequestParam(required = false) String instance) {
        try {
            Long id = resolveInstanceId(instance);
            return ResponseEntity.ok(areas.findByInstanceId(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/entities")
    public ResponseEntity<List<HaEntity>> listEntities(
            @RequestParam(required = false) String instance,
            @RequestParam(required = false) String areaId,
            @RequestParam(required = false) String domain) {
        try {
            Long id = resolveInstanceId(instance);
            if (areaId != null) return ResponseEntity.ok(entities.findByInstanceIdAndAreaId(id, areaId));
            if (domain != null) return ResponseEntity.ok(entities.findByInstanceIdAndDomain(id, domain));
            return ResponseEntity.ok(entities.findByInstanceId(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
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
