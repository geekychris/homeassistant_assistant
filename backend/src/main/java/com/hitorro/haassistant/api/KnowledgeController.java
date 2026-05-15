package com.hitorro.haassistant.api;

import com.hitorro.haassistant.db.EntityAlias;
import com.hitorro.haassistant.db.EntityAliasRepository;
import com.hitorro.haassistant.db.EntityNote;
import com.hitorro.haassistant.db.EntityNoteRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST surface for persistent entity knowledge — what the assistant has learned.
 * Read-only mostly; the assistant writes via tools, but you can also POST to
 * seed knowledge from the UI.
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final EntityNoteRepository notes;
    private final EntityAliasRepository aliases;

    public KnowledgeController(EntityNoteRepository notes, EntityAliasRepository aliases) {
        this.notes = notes;
        this.aliases = aliases;
    }

    @GetMapping("/notes")
    public List<EntityNote> listNotes() { return notes.findAll(); }

    @GetMapping("/notes/{entityId}")
    public ResponseEntity<EntityNote> getNote(@PathVariable String entityId) {
        return notes.findById(entityId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record NoteUpsertRequest(String label, String note) {}

    @PutMapping("/notes/{entityId}")
    public EntityNote upsertNote(@PathVariable String entityId, @RequestBody NoteUpsertRequest req) {
        EntityNote n = notes.findById(entityId).orElse(new EntityNote(entityId));
        if (req.label() != null) n.setLabel(req.label());
        if (req.note() != null) n.setNote(req.note());
        return notes.save(n);
    }

    @DeleteMapping("/notes/{entityId}")
    public ResponseEntity<Void> deleteNote(@PathVariable String entityId) {
        if (!notes.existsById(entityId)) return ResponseEntity.notFound().build();
        notes.deleteById(entityId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/aliases")
    public List<EntityAlias> listAliases() { return aliases.findAll(); }
}
