import { useState } from 'react';

/**
 * Renders a chat tool-message (the JSON result returned by a tool call) in a
 * human-friendly way. Knows about the common shapes our tools return:
 * - arrays of objects → table-ish list of cards
 * - call_ha_service success → ✓ summary
 * - errors → ⚠ red text
 * - everything else → key/value bullets, with a "show JSON" expander.
 */
export default function ToolMessage({
  toolName,
  rawJson,
}: {
  toolName: string | null;
  rawJson: string | null;
}) {
  const [showRaw, setShowRaw] = useState(false);

  const parsed = safeParse(rawJson);
  const summary = summarise(toolName, parsed);
  const isError =
    typeof parsed === 'string' && parsed.startsWith('ERROR:');

  return (
    <div
      style={{
        border: '1px solid var(--border)',
        background: isError ? 'rgba(220, 50, 50, 0.08)' : 'var(--tile)',
        borderRadius: 6,
        padding: 8,
        margin: '4px 0',
        fontSize: 13,
      }}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 8,
        }}
      >
        <span style={{ color: 'var(--muted)' }}>
          {isError ? '⚠ ' : '🔧 '}
          <strong style={{ color: isError ? 'crimson' : 'inherit' }}>{toolName ?? 'tool'}</strong>
          {summary && <span style={{ marginLeft: 8 }}>· {summary}</span>}
        </span>
        <button
          onClick={() => setShowRaw((v) => !v)}
          style={{
            border: 'none',
            background: 'transparent',
            color: 'var(--muted)',
            fontSize: 11,
            cursor: 'pointer',
          }}
        >
          {showRaw ? 'hide raw' : 'show raw'}
        </button>
      </div>
      <Body parsed={parsed} toolName={toolName} />
      {showRaw && (
        <pre
          style={{
            marginTop: 6,
            padding: 6,
            background: 'rgba(0,0,0,0.05)',
            borderRadius: 4,
            fontSize: 11,
            overflowX: 'auto',
            maxHeight: 240,
          }}
        >
          {tryPretty(rawJson)}
        </pre>
      )}
    </div>
  );
}

function Body({ parsed, toolName }: { parsed: unknown; toolName: string | null }) {
  if (parsed == null) return null;
  if (typeof parsed === 'string') {
    return <div style={{ marginTop: 4 }}>{parsed}</div>;
  }
  if (Array.isArray(parsed)) {
    if (parsed.length === 0) return <div style={{ color: 'var(--muted)', marginTop: 4 }}>(empty)</div>;
    if (parsed.every((x) => x && typeof x === 'object')) {
      return <ObjectList rows={parsed as Record<string, unknown>[]} toolName={toolName} />;
    }
    return <div style={{ marginTop: 4 }}>{parsed.map((v) => String(v)).join(', ')}</div>;
  }
  if (typeof parsed === 'object') {
    return <KeyValues obj={parsed as Record<string, unknown>} />;
  }
  return <div style={{ marginTop: 4 }}>{String(parsed)}</div>;
}

function ObjectList({
  rows,
  toolName,
}: {
  rows: Record<string, unknown>[];
  toolName: string | null;
}) {
  // Pick the most useful columns based on what tool produced this.
  const keys = preferredKeys(toolName, rows[0]);
  return (
    <ul style={{ listStyle: 'none', padding: 0, margin: '4px 0 0' }}>
      {rows.slice(0, 30).map((r, i) => (
        <li
          key={i}
          style={{
            padding: '4px 0',
            borderTop: i === 0 ? 'none' : '1px solid var(--border)',
          }}
        >
          <RowSummary row={r} keys={keys} />
        </li>
      ))}
      {rows.length > 30 && (
        <li style={{ color: 'var(--muted)', fontStyle: 'italic', marginTop: 4 }}>
          … and {rows.length - 30} more
        </li>
      )}
    </ul>
  );
}

function RowSummary({ row, keys }: { row: Record<string, unknown>; keys: string[] }) {
  // Headline: friendly_name || name || label || entity_id || id.
  const headline =
    (row['friendly_name'] as string) ||
    (row['name'] as string) ||
    (row['label'] as string) ||
    (row['entity_id'] as string) ||
    (row['alias'] as string) ||
    String(row['id'] ?? '');
  const state = row['state'];
  const meta = keys
    .filter((k) => !['friendly_name', 'name', 'label', 'state', 'entity_id', 'id', 'alias'].includes(k))
    .filter((k) => row[k] != null && row[k] !== '')
    .slice(0, 4)
    .map((k) => `${k}=${formatValue(row[k])}`)
    .join(' · ');
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
      <span>
        <strong>{headline}</strong>
        {meta && <span style={{ color: 'var(--muted)', marginLeft: 6 }}>{meta}</span>}
      </span>
      {state != null && (
        <span style={{ color: stateColor(String(state)) }}>{String(state)}</span>
      )}
    </div>
  );
}

function KeyValues({ obj }: { obj: Record<string, unknown> }) {
  const entries = Object.entries(obj).filter(([, v]) => v != null && v !== '');
  if (entries.length === 0) return null;
  return (
    <ul style={{ listStyle: 'none', padding: 0, margin: '4px 0 0' }}>
      {entries.map(([k, v]) => (
        <li key={k} style={{ padding: '2px 0' }}>
          <span style={{ color: 'var(--muted)' }}>{k}:</span>{' '}
          {typeof v === 'object' ? (
            <code style={{ fontSize: 12 }}>{tryPretty(JSON.stringify(v))}</code>
          ) : (
            String(v)
          )}
        </li>
      ))}
    </ul>
  );
}

function preferredKeys(toolName: string | null, sample: Record<string, unknown> | undefined): string[] {
  if (!sample) return [];
  const all = Object.keys(sample);
  switch (toolName) {
    case 'list_entities':
    case 'find_entity':
      return ['friendly_name', 'entity_id', 'area_name', 'area_id', 'domain', 'device_class', 'unit', 'state', 'label'];
    case 'list_areas':
      return ['name', 'id'];
    case 'list_schedules':
      return ['name', 'cron_expr', 'enabled', 'domain', 'service'];
    case 'list_groupings':
      return ['name', 'description', 'action_count'];
    default:
      return all;
  }
}

function summarise(toolName: string | null, parsed: unknown): string | null {
  if (Array.isArray(parsed)) return `${parsed.length} item${parsed.length === 1 ? '' : 's'}`;
  if (typeof parsed === 'object' && parsed != null) {
    const o = parsed as Record<string, unknown>;
    if (toolName === 'call_ha_service' && o['called']) {
      return `${o['called']} on ${(o['entity_ids'] as unknown[])?.join?.(', ') || '—'}`;
    }
    if (o['fired'] != null && o['failed'] != null) {
      return `${o['fired']} fired, ${o['failed']} failed`;
    }
    if (o['entity_id'] && o['label']) return `${o['label']} (${o['entity_id']})`;
    if (o['entity_id']) return String(o['entity_id']);
  }
  return null;
}

function safeParse(s: string | null): unknown {
  if (s == null || s === '') return null;
  try {
    return JSON.parse(s);
  } catch {
    return s;
  }
}

function tryPretty(s: string | null): string {
  if (!s) return '';
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}

function formatValue(v: unknown): string {
  if (v == null) return '';
  if (typeof v === 'object') return Array.isArray(v) ? `[${v.length}]` : 'obj';
  return String(v);
}

function stateColor(s: string): string {
  if (s === 'on' || s === 'home' || s === 'open') return '#2a7';
  if (s === 'off' || s === 'not_home' || s === 'closed') return 'var(--muted)';
  if (s === 'unavailable' || s === 'unknown') return '#c33';
  return 'inherit';
}
