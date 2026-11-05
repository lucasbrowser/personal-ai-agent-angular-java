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

  afterEach(() => service.stop());

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

    resolvePersistence(preparedTurn());
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(1));
    const contextEvent = JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    expect(contextEvent).toMatchObject({ type: 'conversation.item.create', item: { type: 'message', role: 'system' } });
    expect(contextEvent.item.content[0].text).toContain('previous turn');
    expect(contextEvent.item.content[0].text).not.toContain('What time is it?');

    channel.onmessage?.({ data: JSON.stringify({ event_id: 'context-ack', type: 'conversation.item.added', item: { id: contextEvent.item.id, role: 'system' } }) } as MessageEvent);
    await vi.waitFor(() => expect(channel.send).toHaveBeenCalledTimes(2));
    expect(JSON.parse((channel.send as ReturnType<typeof vi.fn>).mock.calls[1][0])).toMatchObject({ type: 'response.create' });

    // Duplicate completion does not write twice or request a second response.
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'user-final-event', type: 'conversation.item.input_audio_transcription.completed', item_id: 'item-user-1', transcript: 'What time is it?' }) } as MessageEvent);
    expect(conversations.prepareTurn).toHaveBeenCalledTimes(1);
    expect(channel.send).toHaveBeenCalledTimes(2);
    channel.onmessage?.({ data: JSON.stringify({ event_id: 'response-end', type: 'response.done', response: { id: 'response-1', status: 'completed' } }) } as MessageEvent);
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
});
