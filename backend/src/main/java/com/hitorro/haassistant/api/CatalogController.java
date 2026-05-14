package com.hitorro.haassistant.api;

import com.hitorro.haassistant.db.Area;
import com.hitorro.haassistant.db.AreaRepository;
import com.hitorro.haassistant.db.HaEntity;
import com.hitorro.haassistant.db.HaEntityRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final AreaRepository areas;
    private final HaEntityRepository entities;

    public CatalogController(AreaRepository areas, HaEntityRepository entities) {
        this.areas = areas;
        this.entities = entities;
    }

    @GetMapping("/areas")
    public List<Area> listAreas() {
        return areas.findAll();
    }

    @GetMapping("/entities")
    public List<HaEntity> listEntities(
            @RequestParam(required = false) String areaId,
            @RequestParam(required = false) String domain) {
        if (areaId != null) return entities.findByAreaId(areaId);
        if (domain != null) return entities.findByDomain(domain);
        return entities.findAll();
    }
}
