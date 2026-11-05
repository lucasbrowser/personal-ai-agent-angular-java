import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, retry, throwError, timer } from 'rxjs';

export interface VoiceContextMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  createdAt: string;
}

export interface VoiceContextMemory {
  id: string;
  category: string;
  content: string;
  confidence: number | null;
}

export interface PreparedVoiceTurn {
  persistedMessage: { id: string; role: 'USER'; content: string; externalEventId: string | null };
  created: boolean;
  recentMessages: VoiceContextMessage[];
  activeMemories: VoiceContextMemory[];
  contextBudget: {
    maxCharacters: number;
    selectedCharacters: number;
    messageCharacters: number;
    memoryCharacters: number;
    estimation: string;
  };
}

@Injectable({ providedIn: 'root' })
export class VoiceConversationService {
  private readonly http = inject(HttpClient);

  async createConversation(): Promise<string> {
    const conversation = await firstValueFrom(this.http.post<{ id?: unknown } | null>('/api/v1/conversations', {}));
    if (!conversation || typeof conversation.id !== 'string' || !conversation.id.trim()) {
      throw new Error('O servidor nÃ£o retornou uma conversa vÃ¡lida.');
    }
    return conversation.id;
  }

  async prepareTurn(conversationId: string, content: string, externalEventId: string): Promise<PreparedVoiceTurn> {
    const request = this.http.post<PreparedVoiceTurn | null>(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/voice-turns`,
      { content, externalEventId },
    ).pipe(retry({
      count: 1,
      delay: (error: unknown) => error instanceof HttpErrorResponse && (error.status === 0 || error.status >= 500)
        ? timer(150)
        : throwError(() => error),
    }));
    const result = await firstValueFrom(request);
    if (!this.isPreparedTurn(result)) throw new Error('O servidor retornou um contexto de conversa invÃ¡lido.');
    return result;
  }

  private isPreparedTurn(value: PreparedVoiceTurn | null): value is PreparedVoiceTurn {
    return !!value
      && typeof value.created === 'boolean'
      && value.persistedMessage?.role === 'USER'
      && typeof value.persistedMessage.id === 'string'
      && typeof value.persistedMessage.content === 'string'
      && Array.isArray(value.recentMessages)
      && Array.isArray(value.activeMemories)
      && Number.isInteger(value.contextBudget?.maxCharacters)
      && Number.isInteger(value.contextBudget?.selectedCharacters)
      && value.contextBudget.selectedCharacters >= 0
      && value.contextBudget.selectedCharacters <= value.contextBudget.maxCharacters
      && value.recentMessages.every(message => typeof message.content === 'string'
        && (message.role === 'USER' || message.role === 'ASSISTANT'))
      && value.activeMemories.every(memory => typeof memory.content === 'string'
        && typeof memory.category === 'string');
  }
}
