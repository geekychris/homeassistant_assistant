export type Area = { id: string; name: string };

export type HaEntity = {
  entityId: string;
  domain: string;
  name: string | null;
  friendlyName: string | null;
  deviceClass: string | null;
  unit: string | null;
  areaId: string | null;
  deviceId: string | null;
  state: string | null;
  attributes: string | null; // JSON string from HA
};

export type EntityNoteRow = {
  instanceId: number;
  entityId: string;
  label: string | null;
  category: string | null;
  tags: string[] | null;
  note: string | null;
  updatedAt: string;
};

export type Schedule = {
  id: number;
  name: string;
  cronExpr: string;
  enabled: boolean;
  domain: string;
  service: string;
  entityIds: string[] | null;
  serviceData: Record<string, unknown> | null;
  lastRunAt: string | null;
};

export type GroupingAction = {
  id: number;
  entityId: string;
  domain: string;
  service: string;
  serviceData: Record<string, unknown> | null;
  sortOrder: number;
};

export type Grouping = {
  id: number;
  name: string;
  description: string | null;
  actions: GroupingAction[];
};

export type ConversationSession = { id: number; title: string | null };

export type Instance = {
  id: number;
  name: string;
  internalUrl: string | null;
  externalUrl: string | null;
  enabled: boolean;
  isDefault: boolean;
  tokenSet: boolean;
  status: string; // "connected" | "connecting" | "not started" | "disabled"
};

export type ChatMessageRow = {
  id: number;
  sessionId: number;
  role: 'user' | 'assistant' | 'tool' | 'system';
  content: string | null;
  toolName: string | null;
  toolCallsJson: string | null;
  createdAt: string;
};

export type WsEvent =
  | { type: 'state_changed'; entityId: string; state: string; lastChanged: string }
  | { type: 'entity_removed'; entityId: string }
  | { type: 'assistant_message'; sessionId: number; content: string }
  | { type: 'assistant_token'; sessionId: number; turn: number; token: string }
  | { type: 'ask_user'; sessionId: number; question: string };
