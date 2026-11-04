import { TestBed } from '@angular/core/testing';
import { VoiceBrowserAdapter } from './voice-browser.adapter';
import { VoiceEventsService } from './voice-events.service';
import { VoiceSessionService } from './voice-session.service';
import { VoiceWebRTCService } from './voice-webrtc.service';

describe('VoiceWebRTCService', () => {
  let service: VoiceWebRTCService;
  let adapter: any;
  let sessions: any;
  let peer: RTCPeerConnection;
  let channel: RTCDataChannel;
  let stream: MediaStream;
  let track: MediaStreamTrack;
  let audio: HTMLAudioElement;

  beforeEach(() => {
    track = { stop: vi.fn() } as unknown as MediaStreamTrack;
    stream = { getTracks: () => [track], getAudioTracks: () => [track] } as unknown as MediaStream;
    channel = { readyState: 'open', close: vi.fn(), onopen: null, onmessage: null, onerror: null } as unknown as RTCDataChannel;
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
      createSession: vi.fn().mockResolvedValue({ clientSecret: 'ek-ephemeral', expiresAt: 9_999_999_999, sessionId: 'sess-1', model: 'model' }),
    } as unknown as VoiceSessionService;
    TestBed.configureTestingModule({ providers: [VoiceWebRTCService, VoiceEventsService, { provide: VoiceBrowserAdapter, useValue: adapter }, { provide: VoiceSessionService, useValue: sessions }] });
    service = TestBed.inject(VoiceWebRTCService);
  });

  afterEach(() => service.stop());

  it('requests the microphone before obtaining a session and establishes WebRTC with the ephemeral key', async () => {
    const requestMicrophone = vi.fn();
    const microphoneGranted = vi.fn();
    const states: string[] = [];
    service.connectionEvents.subscribe(state => states.push(state));
    await service.start(requestMicrophone, microphoneGranted);

    expect(requestMicrophone.mock.invocationCallOrder[0]).toBeLessThan(adapter.getMicrophone.mock.invocationCallOrder[0]);
    expect(adapter.getMicrophone.mock.invocationCallOrder[0]).toBeLessThan(sessions.createSession.mock.invocationCallOrder[0]);
    expect(microphoneGranted).toHaveBeenCalled();
    expect(peer.addTrack).toHaveBeenCalledWith(track, stream);
    expect(adapter.requestAnswer).toHaveBeenCalledWith('offer-sdp', 'ek-ephemeral');
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
});
