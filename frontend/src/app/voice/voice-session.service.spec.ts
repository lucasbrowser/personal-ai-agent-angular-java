import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { authInterceptor } from '../auth/auth.interceptor';
import { VoiceSessionService } from './voice-session.service';

describe('VoiceSessionService', () => {
  let service: VoiceSessionService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(),
        { provide: AuthService, useValue: { token: () => 'access-token', refresh: () => of(false) } },
        { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
      ],
    });
    service = TestBed.inject(VoiceSessionService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests a session through the authenticated backend endpoint', async () => {
    const result = service.createSession();
    const request = http.expectOne('/api/v1/voice/sessions');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Authorization')).toBe('Bearer access-token');
    expect(request.request.body).toEqual({});
    request.flush({ value: 'ek_temporary', expiresAt: Math.floor(Date.now() / 1000) + 300, sessionId: 'sess-1', model: 'gpt-realtime-2.1-mini' });
    await expect(result).resolves.toMatchObject({ clientSecret: 'ek_temporary', sessionId: 'sess-1' });
  });

  it('rejects expired or incomplete credentials', async () => {
    const expired = service.createSession();
    http.expectOne('/api/v1/voice/sessions').flush({ value: 'ek_old', expiresAt: 1, sessionId: 'sess-1', model: 'model' });
    await expect(expired).rejects.toThrow(/expirou/);

    const invalid = service.createSession();
    http.expectOne('/api/v1/voice/sessions').flush({ value: '', expiresAt: 1, sessionId: '', model: '' });
    await expect(invalid).rejects.toThrow();
  });

  it('rejects missing or malformed credentials and malformed expiration fields', async () => {
    const missing = service.createSession();
    http.expectOne('/api/v1/voice/sessions').flush(null);
    await expect(missing).rejects.toThrow(/inválida/);

    const malformedSecret = service.createSession();
    http.expectOne('/api/v1/voice/sessions').flush({ value: 'not-a-client-secret', expiresAt: Math.floor(Date.now() / 1000) + 300, sessionId: 'sess-1', model: 'model' });
    await expect(malformedSecret).rejects.toThrow(/inválida/);

    const emptySecretPayload = service.createSession();
    http.expectOne('/api/v1/voice/sessions').flush({ value: 'ek_', expiresAt: Math.floor(Date.now() / 1000) + 300, sessionId: 'sess-1', model: 'model' });
    await expect(emptySecretPayload).rejects.toThrow(/inválida/);

    const malformedExpiration = service.createSession();
    http.expectOne('/api/v1/voice/sessions').flush({ value: 'ek_valid', expiresAt: '2099-01-01', sessionId: 'sess-1', model: 'model' });
    await expect(malformedExpiration).rejects.toThrow(/inválida/);
  });

  it('does not proceed when the backend rejects authentication', async () => {
    const result = service.createSession();
    http.expectOne('/api/v1/voice/sessions').flush({}, { status: 401, statusText: 'Unauthorized' });
    await expect(result).rejects.toThrow();
    http.expectNone('/api/v1/voice/sessions');
  });
});
