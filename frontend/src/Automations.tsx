import { useEffect, useState } from 'react';
import { api } from './api';
import type { Grouping, Schedule } from './types';

export default function Automations() {
  const [schedules, setSchedules] = useState<Schedule[]>([]);
  const [groupings, setGroupings] = useState<Grouping[]>([]);
  const [busy, setBusy] = useState(false);

  async function reload() {
    const [s, g] = await Promise.all([api.schedules(), api.groupings()]);
    setSchedules(s);
    setGroupings(g);
  }
  useEffect(() => {
    reload();
  }, []);

  async function toggle(id: number, value: boolean) {
    await api.toggleSchedule(id, value);
    reload();
  }
  async function delSchedule(id: number) {
    if (!confirm('Delete this schedule?')) return;
    await api.deleteSchedule(id);
    reload();
  }
  async function runGrouping(id: number) {
    setBusy(true);
    try {
      const r = await api.runGrouping(id);
      alert(`Ran: ${JSON.stringify(r)}`);
    } finally {
      setBusy(false);
    }
  }
  async function delGrouping(id: number) {
    if (!confirm('Delete this grouping?')) return;
    await api.deleteGrouping(id);
    reload();
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
      <section>
        <h2>Schedules</h2>
        {schedules.length === 0 && (
          <p style={{ color: '#888' }}>None yet. Ask the assistant to create one.</p>
        )}
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {schedules.map((s) => (
            <li
              key={s.id}
              style={{
                border: '1px solid #ddd',
                borderRadius: 6,
                padding: 8,
                marginBottom: 8,
                opacity: s.enabled ? 1 : 0.5,
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <strong>{s.name}</strong>
                <span style={{ fontFamily: 'ui-monospace', color: '#666' }}>{s.cronExpr}</span>
              </div>
              <div style={{ color: '#666', fontSize: 13 }}>
                {s.domain}.{s.service} on {(s.entityIds ?? []).join(', ') || '—'}
              </div>
              <div style={{ marginTop: 6, display: 'flex', gap: 8 }}>
                <button onClick={() => toggle(s.id, !s.enabled)}>
                  {s.enabled ? 'Disable' : 'Enable'}
                </button>
                <button onClick={() => delSchedule(s.id)}>Delete</button>
              </div>
            </li>
          ))}
        </ul>
      </section>
      <section>
        <h2>Groupings</h2>
        {groupings.length === 0 && (
          <p style={{ color: '#888' }}>None yet. Ask the assistant to create one.</p>
        )}
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {groupings.map((g) => (
            <li
              key={g.id}
              style={{
                border: '1px solid #ddd',
                borderRadius: 6,
                padding: 8,
                marginBottom: 8,
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <strong>{g.name}</strong>
                <span style={{ color: '#666' }}>{g.actions.length} actions</span>
              </div>
              {g.description && <div style={{ color: '#666', fontSize: 13 }}>{g.description}</div>}
              <ul style={{ fontSize: 12, color: '#555', margin: '4px 0' }}>
                {g.actions.map((a) => (
                  <li key={a.id}>
                    {a.domain}.{a.service} → {a.entityId}
                  </li>
                ))}
              </ul>
              <div style={{ display: 'flex', gap: 8 }}>
                <button onClick={() => runGrouping(g.id)} disabled={busy}>
                  Run
                </button>
                <button onClick={() => delGrouping(g.id)}>Delete</button>
              </div>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
