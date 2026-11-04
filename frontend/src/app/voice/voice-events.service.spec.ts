import { TestBed } from '@angular/core/testing';
import { VoiceEventsService } from './voice-events.service';
import { VoiceRealtimeEvent } from './voice.models';

describe('VoiceEventsService', () => {
  let service: VoiceEventsService;
  let events: VoiceRealtimeEvent[];

  beforeEach(() => {
    service = TestBed.inject(VoiceEventsService);
    events = [];
    service.events$.subscribe(event => events.push(event));
  });

  it('maps GA VAD, response and transcription events to interface events', () => {
    service.handleMessage(JSON.stringify({ type: 'session.created' }));
    service.handleMessage(JSON.stringify({ type: 'input_audio_buffer.speech_stopped' }));
    service.handleMessage(JSON.stringify({ type: 'response.output_audio.delta', delta: 'audio' }));
    service.handleMessage(JSON.stringify({ type: 'conversation.item.input_audio_transcription.completed', item_id: 'u1', transcript: 'Oi Jarvis' }));
    service.handleMessage(JSON.stringify({ type: 'response.output_audio_transcript.delta', item_id: 'a1', delta: 'Olá!' }));
    service.handleMessage(JSON.stringify({ type: 'response.output_audio_transcript.done', item_id: 'a1', transcript: 'Olá!' }));
    service.handleMessage(JSON.stringify({ type: 'response.done' }));

    expect(events).toEqual([
      { kind: 'state', state: 'LISTENING' },
      { kind: 'state', state: 'THINKING' },
      { kind: 'state', state: 'SPEAKING' },
      { kind: 'transcript', role: 'user', text: 'Oi Jarvis' },
      { kind: 'state', state: 'SPEAKING' },
      { kind: 'transcript', role: 'assistant', text: 'Olá!' },
      { kind: 'state', state: 'LISTENING' },
    ]);
  });

  it('surfaces protocol errors and safely handles invalid messages', () => {
    service.handleMessage(JSON.stringify({ type: 'error', error: { message: 'Sessão encerrada' } }));
    service.handleMessage('{invalid');
    expect(events).toEqual([
      { kind: 'error', message: 'Sessão encerrada' },
      { kind: 'error', message: 'Não foi possível interpretar um evento de voz.' },
    ]);
  });
});
