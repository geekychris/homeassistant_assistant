package com.hitorro.haassistant.api;

import com.hitorro.haassistant.db.EntityAlias;
import com.hitorro.haassistant.db.EntityAliasRepository;
import com.hitorro.haassistant.db.EntityNote;
import com.hitorro.haassistant.db.EntityNoteId;
import com.hitorro.haassistant.db.EntityNoteRepository;
import com.hitorro.haassistant.db.HaInstance;
import com.hitorro.haassistant.db.HaInstanceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final EntityNoteRepository notes;
    private final EntityAliasRepository aliases;
    private final HaInstanceRepository instances;

    public KnowledgeController(EntityNoteRepository notes, EntityAliasRepository aliases,
                               HaInstanceRepository instances) {
        this.notes = notes;
        this.aliases = aliases;
        this.instances = instances;
    }

    @GetMapping("/notes")
    public List<EntityNote> listNotes(@RequestParam(required = false) String instance) {
        if (instance == null || instance.isBlank()) return notes.findAll();
        return notes.findByInstanceId(resolveInstanceId(instance));
    }

    @GetMapping("/notes/{entityId}")
    public ResponseEntity<EntityNote> getNote(@PathVariable String entityId,
                                              @RequestParam(required = false) String instance) {
        try {
            Long id = resolveInstanceId(instance);
            return notes.findById(new EntityNoteId(id, entityId))
                    .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    public record NoteUpsertRequest(String label, String note, String instance) {}

    @PutMapping("/notes/{entityId}")
    public EntityNote upsertNote(@PathVariable String entityId, @RequestBody NoteUpsertRequest req) {
        Long id = resolveInstanceId(req.instance());
        EntityNote n = notes.findById(new EntityNoteId(id, entityId)).orElse(new EntityNote(id, entityId));
        if (req.label() != null) n.setLabel(req.label());
        if (req.note() != null) n.setNote(req.note());
        return notes.save(n);
    }

    @DeleteMapping("/notes/{entityId}")
    public ResponseEntity<Void> deleteNote(@PathVariable String entityId,
                                           @RequestParam(required = false) String instance) {
        Long id = resolveInstanceId(instance);
        EntityNoteId key = new EntityNoteId(id, entityId);
        if (!notes.existsById(key)) return ResponseEntity.notFound().build();
        notes.deleteById(key);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/aliases")
    public List<EntityAlias> listAliases(@RequestParam(required = false) String instance) {
        if (instance == null || instance.isBlank()) return aliases.findAll();
        return aliases.findByInstanceId(resolveInstanceId(instance));
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
