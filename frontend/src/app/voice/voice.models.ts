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
  | { kind: 'error'; message: string };
