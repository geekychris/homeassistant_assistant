import type {
  Area, ChatMessageRow, ConversationSession, Grouping, HaEntity, Schedule,
} from './types';

async function get<T>(url: string): Promise<T> {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${url}: ${r.status}`);
  return r.json();
}

async function send<T>(method: string, url: string, body?: unknown): Promise<T> {
  const r = await fetch(url, {
    method,
    headers: { 'content-type': 'application/json' },
    body: body == null ? undefined : JSON.stringify(body),
  });
  if (!r.ok) throw new Error(`${method} ${url}: ${r.status}`);
  if (r.status === 204) return undefined as T;
  return r.json();
}

export const api = {
  areas: () => get<Area[]>('/api/areas'),
  entities: () => get<HaEntity[]>('/api/entities'),

  schedules: () => get<Schedule[]>('/api/schedules'),
  createSchedule: (s: Partial<Schedule>) => send<Schedule>('POST', '/api/schedules', s),
  toggleSchedule: (id: number, value: boolean) =>
    send<Schedule>('PUT', `/api/schedules/${id}/enabled?value=${value}`),
  deleteSchedule: (id: number) => send<void>('DELETE', `/api/schedules/${id}`),

  groupings: () => get<Grouping[]>('/api/groupings'),
  runGrouping: (id: number) => send<unknown>('POST', `/api/groupings/${id}/run`),
  deleteGrouping: (id: number) => send<void>('DELETE', `/api/groupings/${id}`),

  sessions: () => get<ConversationSession[]>('/api/chat/sessions'),
  createSession: (title?: string) =>
    send<ConversationSession>('POST', '/api/chat/sessions', { title: title ?? 'New chat' }),
  messages: (id: number) => get<ChatMessageRow[]>(`/api/chat/sessions/${id}/messages`),
  send: (id: number, text: string) =>
    send<{ assistantText: string; awaitingUser: boolean; userPrompt: string | null }>(
      'POST', `/api/chat/sessions/${id}/messages`, { text }),
};

export function openWs(): WebSocket {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  return new WebSocket(`${proto}://${location.host}/ws/ui`);
}
