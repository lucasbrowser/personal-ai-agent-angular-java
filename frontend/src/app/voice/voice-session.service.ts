import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { VoiceSession } from './voice.models';

interface VoiceSessionResponse {
  value: string;
  expiresAt: number;
  sessionId: string;
  model: string;
}

@Injectable({ providedIn: 'root' })
export class VoiceSessionService {
  private readonly http = inject(HttpClient);

  async createSession(): Promise<VoiceSession> {
    const session = await firstValueFrom(this.http.post<VoiceSessionResponse | null>('/api/v1/voice/sessions', {}));
    if (!session || typeof session.value !== 'string' || !session.value.startsWith('ek_') || session.value.length <= 3
      || typeof session.sessionId !== 'string' || !session.sessionId.trim()
      || typeof session.model !== 'string' || !session.model.trim() || typeof session.expiresAt !== 'number'
      || !Number.isFinite(session.expiresAt)) {
      throw new Error('O servidor retornou uma credencial de voz inválida.');
    }
    if (session.expiresAt <= Math.floor(Date.now() / 1000)) {
      throw new Error('A credencial de voz expirou. Tente novamente.');
    }
    return {
      clientSecret: session.value,
      expiresAt: session.expiresAt,
      sessionId: session.sessionId,
      model: session.model,
    };
  }
}
