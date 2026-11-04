import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { VoiceSession } from './voice.models';

interface VoiceSessionResponse {
  clientSecret: string;
  expiresAt: number;
  sessionId: string;
  model: string;
}

@Injectable({ providedIn: 'root' })
export class VoiceSessionService {
  private readonly http = inject(HttpClient);

  async createSession(): Promise<VoiceSession> {
    const session = await firstValueFrom(this.http.post<VoiceSessionResponse>('/api/v1/voice/sessions', {}));
    if (!session.clientSecret || !session.sessionId || !session.model || !Number.isFinite(session.expiresAt)) {
      throw new Error('O servidor retornou uma credencial de voz inválida.');
    }
    if (session.expiresAt <= Math.floor(Date.now() / 1000)) {
      throw new Error('A credencial de voz expirou. Tente novamente.');
    }
    return session;
  }
}
