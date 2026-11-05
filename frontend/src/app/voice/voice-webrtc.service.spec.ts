import { TestBed } from '@angular/core/testing';
import { VoiceBrowserAdapter } from './voice-browser.adapter';
import { PreparedVoiceTurn, VoiceConversationService } from './voice-conversation.service';
import { VoiceEventsService } from './voice-events.service';
import { VoiceSessionService } from './voice-session.service';
import { VoiceWebRTCService } from './voice-webrtc.service';

describe('VoiceWebRTCService', () => {
  let service: VoiceWebRTCService;
  let adapter: any;
  let sessions: any;
  let conversations: any;
  let events: VoiceEventsService;
  let peer: RTCPeerConnection;
  let channel: RTCDataChannel;
  let stream: MediaStream;
  let track: MediaStreamTrack;
  let audio: HTMLAudioElement;

  beforeEach(() => {
    track = { stop: vi.fn() } as unknown as MediaStreamTrack;
    stream = { getTracks: () => [track], getAudioTracks: () => [track] } as unknown as MediaStream;
    channel = { readyState: 'open', close: vi.fn(), send: vi.fn(), onopen: null, onmessage: null, onerror: null } as unknown as RTCDataChannel;
    const peerMock: any = {
      connectionState: 'new', signalingState: 'stable', localDescription: { type: 'offer', sdp: 'offer-sdp' },
      addTrack: vi.fn(), createDataChannel: vi.fn().mockReturnValue(channel),
      createOffer: vi.fn().mockResolvedValue({ type: 'offer', sdp: 'offer-sdp' }),
      setLocalDescription: vi.fn().mockResolvedValue(undefined), setRemoteDescription: vi.fn().mockResolvedValue(undefined),
      close: vi.fn(), ontrack: null, onconnectionstatechange: null,
    };
    peer = peerMock as RTCPeerConnection;
    audio = document.createElement('audio');
    vi.spyOn(audio, 'play').mockResolvedValue(undefined);
    vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    adapter = {
      createPeerConnection: vi.fn().mockReturnValue(peer), getMicrophone: vi.fn().mockResolvedValue(stream),
      createAudioElement: vi.fn().mockReturnValue(audio), requestAnswer: vi.fn().mockResolvedValue('answer-sdp'),
    } as unknown as VoiceBrowserAdapter;
    sessions = {
      createSession: vi.fn().mockResolvedValue({ clientSecret: 'ek_ephemeral', expiresAt: 9_999_999_999, sessionId: 'sess-1', model: 'model' }),
    } as unknown as VoiceSessionService;
    conversations = {
      createConversation: vi.fn().mockResolvedValue('conversation-1'),
      prepareTurn: vi.fn().mockResolvedValue(preparedTurn()),
    } as unknown as VoiceConversationService;
    TestBed.configureTestingModule({ providers: [VoiceWebRTCService, VoiceEventsService,
      { provide: VoiceBrowserAdapter, useValue: adapter },
      { provide: VoiceSessionService, useValue: sessions },
      { provide: VoiceConversationService, useValue: conversations }] });
    service = TestBed.inject(VoiceWebRTCService);
    events = TestBed.inject(VoiceEventsService);
  });

  afterEach(() => { service.stop(); vi.useRealTimers(); });

  it('requests the microphone before obtaining a session and establishes WebRTC with the ephemeral key', async () => {
    const requestMicrophone = vi.fn();
    const microphoneGranted = vi.fn();
    const states: string[] = [];
    service.connectionEvents.subscribe(state => states.push(state));
    await service.start(requestMicrophone, microphoneGranted);

    expect(requestMicrophone.mock.invocationCallOrder[0]).toBeLessThan(adapter.getMicrophone.mock.invocationCallOrder[0]);
    expect(adapter.getMicrophone.mock.invocationCallOrder[0]).toBeLessThan(conversations.createConversation.mock.invocationCallOrder[0]);
    expect(conversations.createConversation.mock.invocationCallOrder[0]).toBeLessThan(sessions.createSession.mock.invocationCallOrder[0]);
    expect(sessions.createSession).toHaveBeenCalledWith('conversation-1');
    expect(sessions.createSession.mock.invocationCallOrder[0]).toBeLessThan(adapter.createPeerConnection.mock.invocationCallOrder[0]);
    expect(microphoneGranted).toHaveBeenCalled();
    expect(peer.addTrack).toHaveBeenCalledWith(track, stream);
    expect(adapter.requestAnswer).toHaveBeenCalledWith('offer-sdp', 'ek_ephemeral');
    expect(peer.setRemoteDescription).toHaveBeenCalledWith({ type: 'answer', sdp: 'answer-sdp' });
    expect(document.body.contains(audio)).toBe(true);
    channel.onopen?.(new Event('open'));
    expect(states).toEqual(['connected']);
  });

  it('prevents duplicate connections and releases every resource on stop', async () => {
    await service.start(() => undefined);
    await expect(service.start(() => undefined)).rejects.toThrow(/ativa/);
    service.stop();

    expect(track.stop).toHaveBeenCalled();
    expect(channel.close).toHaveBeenCalled();
    expect(peer.close).toHaveBeenCalled();
    expect(document.body.contains(audio)).toBe(false);
    expect(peer.ontrack).toBeNull();
    expect(channel.onmessage).toBeNull();
  });

  it('cleans up when microphone permission is denied before requesting a session', async () => {
    adapter.getMicrophone.mockRejectedValue(new DOMException('denied', 'NotAllowedError'));
    await expect(service.start(() => undefined)).rejects.toThrow('denied');
    expect(sessions.createSession).not.toHaveBeenCalled();
    expect(track.stop).not.toHaveBeenCalled();
  });

  it('cleans up after a provider connection failure', async () => {
    adapter.requestAnswer.mockRejectedValue(new Error('offline'));
    await expect(service.start(() => undefined)).rejects.toThrow('offline');
    expect(track.stop).toHaveBeenCalled();
    expect(peer.close).toHaveBeenCalled();
  });

  it('waits for persisted user transcript, then context acknowledgement, before one response.create', async () => {
    let resolvePersistence!: (value: PreparedVoiceTurn) => void;
    conversations.prepareTurn.mockImplementation(() => new Promise(resolve => { resolvePersistence = resolve; }));
    await service.start(() => undefined);

    // Transcription can precede the VAD stop event; buffer it until the item is committed.
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'user-final-event', type: 'conversation.item.input_audio_transcription.completed', item_id: 'item-user-1', transcript: 'What time is it?' }) } as MessageEvent);
    expect(conversations.prepareTurn).not.toHaveBeenCalled();
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'user-stop-event', type: 'input_audio_buffer.speech_stopped', item_id: 'item-user-1', audio_end_ms: 1200 }) } as MessageEvent);
    expect(conversations.prepareTurn).toHaveBeenCalledWith('conversation-1', 'What time is it?', 'user-final-event');
    expect(channel.send).not.toHaveBeenCalled();

    const persisted = preparedTurn();
    resolvePersistence({ ...persisted, recentMessages: [
      { id: 'old-user', role: 'USER', content: 'previous turn', createdAt: '2026-01-01T00:00:00Z' },
      { id: 'old-assistant', role: 'ASSISTANT', content: 'previous answer', createdAt: '2026-01-01T00:00:01Z' },
      { id: 'current-message', role: 'USER', content: 'What time is it?', createdAt: '2026-01-01T00:00:02Z' },
    ] });
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(1));
    const contextEvent = JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    expect(contextEvent).toMatchObject({ type: 'conversation.item.create', previous_item_id: 'root', item: { type: 'message', role: 'user' } });
    expect(contextEvent.item.content[0].text).toContain('previous turn');
    expect(contextEvent.item.content[0].text).not.toContain('What time is it?');
    const contextPayload = JSON.parse(contextEvent.item.content[0].text.slice(contextEvent.item.content[0].text.indexOf('{')));
    expect(contextPayload.historicalMessages).toEqual([
      { role: 'USER', content: 'previous turn' },
      { role: 'ASSISTANT', content: 'previous answer' },
    ]);
    expect(contextPayload.relevantMemories).toEqual([{ category: 'PREFERENCE', content: 'short answers' }]);

    channel.onmessage?.({ data: JSON.stringify({ event_id: 'context-ack', type: 'conversation.item.added', previous_item_id: null, item: { id: contextEvent.item.id, type: 'message', role: 'user' } }) } as MessageEvent);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(2));
    expect(JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[1][0])).toMatchObject({ type: 'response.create' });
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'duplicate-context-ack', type: 'conversation.item.added',
      previous_item_id: null, item: { id: contextEvent.item.id, type: 'message', role: 'system' } }) } as MessageEvent);

    // Duplicate completion does not write twice or request a second response.
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'user-final-event', type: 'conversation.item.input_audio_transcription.completed', item_id: 'item-user-1', transcript: 'What time is it?' }) } as MessageEvent);
    expect(conversations.prepareTurn).toHaveBeenCalledTimes(1);
    expect(channel.send).toHaveBeenCalledTimes(2);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'response-end', type: 'response.done', response: { id: 'response-1', status: 'completed' } }) } as MessageEvent);
  });

  it('bootstraps context once, then only persists the next turn and requests one response', async () => {
    conversations.prepareTurn.mockImplementation((_id: string, content: string, eventId: string) =>
      Promise.resolve(preparedTurn(eventId, content)));
    await service.start(() => undefined);
    emitTurn('item-first', 'First question', 'final-first', 10);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(1));
    const context = JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'ack-first', type: 'conversation.item.added',
      previous_item_id: null, item: { id: context.item.id, type: 'message', role: 'user' } }) } as MessageEvent);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(2));
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'response-first', type: 'response.done',
      response: { id: 'r-first', status: 'completed' } }) } as MessageEvent);

    emitTurn('item-second', 'Second question', 'final-second', 20);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(3));
    expect(JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[1][0]).type).toBe('response.create');
    expect(JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[2][0]).type).toBe('response.create');
    expect(channel.send).toHaveBeenCalledTimes(3);
    expect(conversations.prepareTurn).toHaveBeenCalledTimes(2);
    expect(conversations.prepareTurn).toHaveBeenNthCalledWith(2, 'conversation-1', 'Second question', 'final-second');
  });

  it('marks an empty first context initialized and does not inject later history or memories', async () => {
    conversations.prepareTurn.mockImplementation((_id: string, content: string, eventId: string) =>
      Promise.resolve({ ...preparedTurn(eventId, content), recentMessages: [], activeMemories: [] }));
    await service.start(() => undefined);
    emitTurn('item-empty-first', 'First', 'empty-first', 10);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(1));
    expect(JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[0][0]).type).toBe('response.create');
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'response-empty', type: 'response.done',
      response: { id: 'r-empty', status: 'completed' } }) } as MessageEvent);
    conversations.prepareTurn.mockImplementation((_id: string, content: string, eventId: string) =>
      Promise.resolve(preparedTurn(eventId, content)));
    emitTurn('item-empty-second', 'Second', 'empty-second', 20);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(2));
    expect(JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[1][0]).type).toBe('response.create');
  });

  it('keeps the serialized context within the server character budget while preserving retained order', async () => {
    const prepared = preparedTurn('budget-final', 'What time is it?');
    conversations.prepareTurn.mockResolvedValue({ ...prepared, contextBudget: {
      ...prepared.contextBudget, maxCharacters: 500,
    }, recentMessages: [
      { id: 'history-1', role: 'USER', content: 'A'.repeat(120), createdAt: '2026-01-01T00:00:00Z' },
      { id: 'history-2', role: 'ASSISTANT', content: 'B'.repeat(120), createdAt: '2026-01-01T00:00:01Z' },
      { id: 'history-3', role: 'USER', content: 'C'.repeat(80), createdAt: '2026-01-01T00:00:02Z' },
      { id: 'current-message', role: 'USER', content: 'What time is it?', createdAt: '2026-01-01T00:00:03Z' },
    ], activeMemories: [{ id: 'memory-1', category: 'PREFERENCE', content: 'D'.repeat(60), confidence: 0.9 }] });
    await service.start(() => undefined);
    emitTurn('item-budget', 'What time is it?', 'budget-final', 10);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(1));
    const create = JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    const text = create.item.content[0].text as string;
    expect(text.length).toBeLessThanOrEqual(500);
    const payload = JSON.parse(text.slice(text.indexOf('{')));
    expect(payload.historicalMessages.at(-1)).toEqual({ role: 'USER', content: 'C'.repeat(80) });
    expect(payload.historicalMessages).not.toContainEqual({ role: 'USER', content: 'What time is it?' });
  });

  it('recovers a lost context acknowledgement by retrieving the stable item id without resending create', async () => {
    vi.useFakeTimers();
    conversations.prepareTurn.mockImplementation((_id: string, content: string, eventId: string) =>
      Promise.resolve(preparedTurn(eventId, content)));
    await service.start(() => undefined);
    emitTurn('item-timeout', 'Hello', 'final-timeout', 10);
    await vi.advanceTimersByTimeAsync(8_000);
    await Promise.resolve();
    expect(channel.send).toHaveBeenCalledTimes(2);
    const create = JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    const retrieve = JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[1][0]);
    expect(create).toMatchObject({ type: 'conversation.item.create', previous_item_id: 'root', item: { role: 'user' } });
    expect(retrieve).toMatchObject({ type: 'conversation.item.retrieve', item_id: create.item.id });

    channel.onmessage?.({ data: JSON.stringify({ event_id: 'retrieved-context', type: 'conversation.item.retrieved',
      item: { id: create.item.id, type: 'message', role: 'user' } }) } as MessageEvent);
    vi.useRealTimers();
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(3));
    expect(channel.send).toHaveBeenCalledTimes(3);
    expect(JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[2][0]).type).toBe('response.create');
  });

  it('fails closed when context insertion cannot be confirmed after one retrieval attempt', async () => {
    vi.useFakeTimers();
    conversations.prepareTurn.mockImplementation((_id: string, content: string, eventId: string) =>
      Promise.resolve(preparedTurn(eventId, content)));
    const received: any[] = [];
    events.events$.subscribe(event => received.push(event));
    await service.start(() => undefined);
    emitTurn('item-uncertain', 'Hello', 'final-uncertain', 10);
    await vi.advanceTimersByTimeAsync(13_000);
    await Promise.resolve();
    await Promise.resolve();
    expect(channel.send).toHaveBeenCalledTimes(2);
    expect(JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[0][0]).type).toBe('conversation.item.create');
    expect(JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[1][0]).type).toBe('conversation.item.retrieve');
    expect(received.some(event => event.kind === 'error' && event.message.includes('nenhuma resposta foi solicitada'))).toBe(true);

    // Even a later turn cannot retry create within the uncertain session.
    vi.useRealTimers();
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'response-not-sent', type: 'response.done',
      response: { id: 'unexpected', status: 'completed' } }) } as MessageEvent);
    emitTurn('item-after-uncertain', 'Another', 'final-after-uncertain', 20);
    await Promise.resolve();
    expect(channel.send).toHaveBeenCalledTimes(2);
  });

  it('rejects a context acknowledgement that reports a non-root predecessor', async () => {
    conversations.prepareTurn.mockImplementation((_id: string, content: string, eventId: string) =>
      Promise.resolve(preparedTurn(eventId, content)));
    const received: any[] = [];
    events.events$.subscribe(event => received.push(event));
    await service.start(() => undefined);
    emitTurn('item-wrong-position', 'Hello', 'wrong-position-final', 10);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(1));
    const create = JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'wrong-position-ack', type: 'conversation.item.added',
      previous_item_id: 'item-before', item: { id: create.item.id, type: 'message', role: 'system' } }) } as MessageEvent);
    await vi.waitFor(() => expect(received.some(event => event.kind === 'error')).toBe(true));
    expect(channel.send).toHaveBeenCalledTimes(1);
  });

  it('requires a fresh bootstrap for a new session and ignores an old channel callback', async () => {
    conversations.prepareTurn.mockImplementation((_id: string, content: string, eventId: string) =>
      Promise.resolve(preparedTurn(eventId, content)));
    await service.start(() => undefined);
    emitTurn('item-old-session', 'Old', 'old-final', 10);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(1));
    const oldMessageHandler = channel.onmessage;
    const firstContext = JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'old-context-ack', type: 'conversation.item.added',
      previous_item_id: null, item: { id: firstContext.item.id, type: 'message', role: 'user' } }) } as MessageEvent);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(2));
    service.stop();
    (channel.send as ReturnType<typeof vi.fn>).mockClear();
    await service.start(() => undefined);
    emitTurn('item-new-session', 'New', 'new-final', 10);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(1));
    const newContext = JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    expect(newContext.item.id).not.toBe(firstContext.item.id);
    oldMessageHandler?.call(channel, { data: JSON.stringify({ event_id: 'late-old-ack', type: 'conversation.item.added',
      previous_item_id: null, item: { id: firstContext.item.id, type: 'message', role: 'user' } }) } as MessageEvent);
    expect(channel.send).toHaveBeenCalledTimes(1);
  });

  it('does not request a response when the session disconnects during context bootstrap', async () => {
    conversations.prepareTurn.mockImplementation((_id: string, content: string, eventId: string) =>
      Promise.resolve(preparedTurn(eventId, content)));
    await service.start(() => undefined);
    emitTurn('item-disconnect', 'Hello', 'disconnect-final', 10);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(1));
    service.stop();
    await Promise.resolve();
    expect(channel.send).toHaveBeenCalledTimes(1);
    expect(JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[0][0]).type).toBe('conversation.item.create');
  });

  it('does not generate a response after persistence or context insertion failure', async () => {
    conversations.prepareTurn.mockRejectedValue(new Error('database unavailable'));
    const received: any[] = [];
    events.events$.subscribe(event => received.push(event));
    await service.start(() => undefined);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'stop-1', type: 'input_audio_buffer.speech_stopped', item_id: 'item-1' }) } as MessageEvent);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'final-1', type: 'conversation.item.input_audio_transcription.completed', item_id: 'item-1', transcript: 'Hello' }) } as MessageEvent);
    await vi.waitFor(() => expect(received.some(event => event.kind === 'error')).toBe(true));
    expect(channel.send).not.toHaveBeenCalled();

    // A successful persistence followed by a provider error for context insertion also blocks response.create.
    service.stop();
    conversations.prepareTurn.mockImplementation((_id: string, content: string, eventId: string) =>
      Promise.resolve(preparedTurn(eventId, content)));
    await service.start(() => undefined);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'stop-2', type: 'input_audio_buffer.speech_stopped', item_id: 'item-2' }) } as MessageEvent);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'final-2', type: 'conversation.item.input_audio_transcription.completed', item_id: 'item-2', transcript: 'Hello again' }) } as MessageEvent);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(1));
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'provider-error', type: 'error', error: { message: 'context rejected' } }) } as MessageEvent);
    await Promise.resolve();
    expect(channel.send).toHaveBeenCalledTimes(1);
  });

  it('waits for the active response to finish before processing another turn', async () => {
    conversations.prepareTurn.mockImplementation((_id: string, content: string, eventId: string) => Promise.resolve({
      ...preparedTurn(eventId, content), recentMessages: [], activeMemories: [],
    }));
    await service.start(() => undefined);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'stop-a', type: 'input_audio_buffer.speech_stopped', item_id: 'item-a', audio_end_ms: 10 }) } as MessageEvent);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'final-a', type: 'conversation.item.input_audio_transcription.completed', item_id: 'item-a', transcript: 'First' }) } as MessageEvent);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(1));
    expect(JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[0][0]).type).toBe('response.create');

    channel.onmessage?.({ data: JSON.stringify({ event_id: 'stop-b', type: 'input_audio_buffer.speech_stopped', item_id: 'item-b', audio_end_ms: 20 }) } as MessageEvent);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'final-b', type: 'conversation.item.input_audio_transcription.completed', item_id: 'item-b', transcript: 'Second' }) } as MessageEvent);
    expect(conversations.prepareTurn).toHaveBeenCalledTimes(1);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'response-end-a', type: 'response.done', response: { id: 'response-a', status: 'completed' } }) } as MessageEvent);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(2));
    expect(JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[1][0]).type).toBe('response.create');
    expect(conversations.prepareTurn).toHaveBeenCalledTimes(2);
  });

  function preparedTurn(eventId = 'user-final-event', content = 'What time is it?'): PreparedVoiceTurn {
    return {
      persistedMessage: { id: 'current-message', role: 'USER', content, externalEventId: eventId },
      created: true,
      recentMessages: [{ id: 'previous-message', role: 'USER', content: 'previous turn', createdAt: new Date().toISOString() },
        { id: 'current-message', role: 'USER', content, createdAt: new Date().toISOString() }],
      activeMemories: [{ id: 'memory-1', category: 'PREFERENCE', content: 'short answers', confidence: 0.9 }],
      contextBudget: { maxCharacters: 1000, selectedCharacters: 100, messageCharacters: 40, memoryCharacters: 60, estimation: 'chars' },
    };
  }

  function emitTurn(itemId: string, text: string, eventId: string, audioEndMs: number): void {
    channel.onmessage?.({ data: JSON.stringify({ event_id: `${eventId}-stop`, type: 'input_audio_buffer.speech_stopped',
      item_id: itemId, audio_end_ms: audioEndMs }) } as MessageEvent);
    channel.onmessage?.({ data: JSON.stringify({ event_id: eventId, type: 'conversation.item.input_audio_transcription.completed',
      item_id: itemId, transcript: text }) } as MessageEvent);
  }
});
