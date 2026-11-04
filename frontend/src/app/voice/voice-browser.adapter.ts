import { Injectable } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class VoiceBrowserAdapter {
  createPeerConnection(): RTCPeerConnection { return new RTCPeerConnection(); }
  getMicrophone(): Promise<MediaStream> {
    if (!navigator.mediaDevices?.getUserMedia) return Promise.reject(new Error('Este navegador não oferece acesso ao microfone.'));
    return navigator.mediaDevices.getUserMedia({ audio: true });
  }
  createAudioElement(): HTMLAudioElement { return document.createElement('audio'); }
  async requestAnswer(sdp: string, clientSecret: string): Promise<string> {
    const response = await fetch('https://api.openai.com/v1/realtime/calls', {
      method: 'POST',
      headers: { Authorization: `Bearer ${clientSecret}`, 'Content-Type': 'application/sdp' },
      body: sdp,
    });
    if (!response.ok) throw new Error(response.status === 401 ? 'A credencial de voz expirou. Inicie uma nova sessão.' : 'Não foi possível conectar ao serviço de voz.');
    return response.text();
  }
}
