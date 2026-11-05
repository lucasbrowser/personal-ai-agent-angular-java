export type VoiceState =
  | 'IDLE'
  | 'REQUESTING_MICROPHONE'
  | 'CONNECTING'
  | 'LISTENING'
  | 'THINKING'
  | 'SPEAKING'
  | 'DISCONNECTING'
  | 'ERROR';

export interface VoiceSession {
  clientSecret: string;
  expiresAt: number;
  sessionId: string;
  model: string;
}

export type VoiceRealtimeEvent =
  | { kind: 'state'; state: 'LISTENING' | 'THINKING' | 'SPEAKING' }
  | { kind: 'transcript'; role: 'user' | 'assistant'; text: string }
  | { kind: 'user-turn-committed'; itemId: string; audioEndMs?: number }
  | { kind: 'user-turn-final'; eventId: string; itemId: string; text: string }
  | { kind: 'user-transcription-failed'; itemId: string; message: string }
  | { kind: 'context-item-added'; itemId: string; previousItemId: string | null }
  | { kind: 'context-item-retrieved'; itemId: string }
  | { kind: 'response-started'; responseId: string }
  | { kind: 'response-finished'; responseId?: string; status: 'completed' | 'cancelled' | 'failed' | 'incomplete' | 'unknown' }
  | { kind: 'turn-notice'; message: string }
  | { kind: 'error'; message: string };
