import { Injectable, inject } from '@angular/core';
import { Subject } from 'rxjs';
import { VoiceBrowserAdapter } from './voice-browser.adapter';
import { VoiceEventsService } from './voice-events.service';
import { VoiceSessionService } from './voice-session.service';

@Injectable({ providedIn: 'root' })
export class VoiceWebRTCService {
  private readonly browser = inject(VoiceBrowserAdapter);
  private readonly sessions = inject(VoiceSessionService);
  private readonly events = inject(VoiceEventsService);
  private peer?: RTCPeerConnection;
  private dataChannel?: RTCDataChannel;
  private microphone?: MediaStream;
  private audio?: HTMLAudioElement;
  private active = false;
  readonly connectionEvents = new Subject<'connected' | 'failed'>();

  async start(onMicrophoneRequest: () => void, onMicrophoneGranted: () => void = () => undefined): Promise<void> {
    if (this.active) throw new Error('Já existe uma sessão de voz ativa.');
    this.active = true;
    try {
      onMicrophoneRequest();
      this.microphone = await this.browser.getMicrophone();
      onMicrophoneGranted();
      const session = await this.sessions.createSession();
      const peer = this.browser.createPeerConnection();
      this.peer = peer;
      const audio = this.browser.createAudioElement();
      this.audio = audio;
      audio.autoplay = true;
      audio.setAttribute('playsinline', '');
      audio.hidden = true;
      document.body.append(audio);
      peer.ontrack = event => {
        this.audio!.srcObject = event.streams[0] ?? null;
        void this.audio!.play().catch(() => undefined);
      };
      peer.onconnectionstatechange = () => {
        if (peer.connectionState === 'failed' || peer.connectionState === 'disconnected') {
          this.connectionEvents.next('failed');
        }
      };
      for (const track of this.microphone.getAudioTracks()) peer.addTrack(track, this.microphone);
      const channel = peer.createDataChannel('oai-events');
      this.dataChannel = channel;
      channel.onopen = () => this.connectionEvents.next('connected');
      channel.onmessage = event => this.events.handleMessage(String(event.data));
      channel.onerror = () => this.connectionEvents.next('failed');

      const offer = await peer.createOffer();
      await peer.setLocalDescription(offer);
      const localSdp = peer.localDescription?.sdp;
      if (!localSdp) throw new Error('O navegador não conseguiu criar a oferta de áudio.');
      const answer = await this.browser.requestAnswer(localSdp, session.clientSecret);
      if (!answer.trim()) throw new Error('O serviço de voz retornou uma resposta SDP vazia.');
      await peer.setRemoteDescription({ type: 'answer', sdp: answer });
    } catch (error) {
      this.stop();
      throw error;
    }
  }

  stop(): void {
    this.active = false;
    if (this.dataChannel) {
      this.dataChannel.onopen = null;
      this.dataChannel.onmessage = null;
      this.dataChannel.onerror = null;
      if (this.dataChannel.readyState !== 'closed') this.dataChannel.close();
      this.dataChannel = undefined;
    }
    if (this.peer) {
      this.peer.ontrack = null;
      this.peer.onconnectionstatechange = null;
      if (this.peer.signalingState !== 'closed') this.peer.close();
      this.peer = undefined;
    }
    this.microphone?.getTracks().forEach(track => track.stop());
    this.microphone = undefined;
    if (this.audio) {
      this.audio.pause();
      this.audio.srcObject = null;
      this.audio.remove();
      this.audio = undefined;
    }
    this.events.clear();
  }
}
