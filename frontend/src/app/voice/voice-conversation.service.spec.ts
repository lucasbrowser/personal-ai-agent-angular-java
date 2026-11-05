import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { authInterceptor } from '../auth/auth.interceptor';
import { VoiceConversationService } from './voice-conversation.service';

describe('VoiceConversationService', () => {
  let service: VoiceConversationService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(),
      { provide: AuthService, useValue: { token: () => 'access-token', refresh: () => of(false) } },
      { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
    ] });
    service = TestBed.inject(VoiceConversationService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('creates a conversation and returns the backend UUID', async () => {
    const pending = service.createConversation();
    const request = http.expectOne('/api/v1/conversations');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Authorization')).toBe('Bearer access-token');
    expect(request.request.body).toEqual({});
    request.flush({ id: 'conversation-uuid' });
    await expect(pending).resolves.toBe('conversation-uuid');
  });

  it('posts only transcript and event id and retries transient failure with the same idempotency key', async () => {
    const pending = service.prepareTurn('conversation-uuid', 'Hello Jarvis', 'event-123');
    const first = http.expectOne('/api/v1/conversations/conversation-uuid/voice-turns');
    expect(first.request.method).toBe('POST');
    expect(first.request.headers.get('Authorization')).toBe('Bearer access-token');
    expect(first.request.body).toEqual({ content: 'Hello Jarvis', externalEventId: 'event-123' });
    first.flush({ error: { code: 'TEMPORARY', message: 'retry' } }, { status: 503, statusText: 'Unavailable' });

    await new Promise(resolve => setTimeout(resolve, 200));
    const retry = http.expectOne('/api/v1/conversations/conversation-uuid/voice-turns');
    expect(retry.request.body).toEqual({ content: 'Hello Jarvis', externalEventId: 'event-123' });
    retry.flush(preparedTurn());
    await expect(pending).resolves.toMatchObject({ created: true, persistedMessage: { role: 'USER' } });
  });

  it('does not retry client errors and rejects malformed backend context', async () => {
    const badRequest = service.prepareTurn('conversation-uuid', 'Hello', 'event-400');
    http.expectOne('/api/v1/conversations/conversation-uuid/voice-turns')
      .flush({ error: { code: 'INVALID_REQUEST' } }, { status: 400, statusText: 'Bad Request' });
    await expect(badRequest).rejects.toThrow();
    http.expectNone('/api/v1/conversations/conversation-uuid/voice-turns');

    const malformed = service.prepareTurn('conversation-uuid', 'Hello', 'event-invalid');
    http.expectOne('/api/v1/conversations/conversation-uuid/voice-turns').flush({ created: true });
    await expect(malformed).rejects.toThrow(/invÃ¡lido/);
  });

  function preparedTurn() {
    return {
      persistedMessage: { id: 'message-1', role: 'USER', content: 'Hello Jarvis', externalEventId: 'event-123' },
      created: true,
      recentMessages: [],
      activeMemories: [],
      contextBudget: { maxCharacters: 1000, selectedCharacters: 0, messageCharacters: 0, memoryCharacters: 0, estimation: 'chars' },
    };
  }
});
