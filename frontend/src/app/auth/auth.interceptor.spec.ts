import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors, HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';
import { AuthResponse } from './auth.models';

const session: AuthResponse = {
  accessToken: 'initial-access',
  user: { id: 'user-1', name: 'Admin', email: 'admin@example.test', role: 'ADMIN' },
};

describe('authInterceptor', () => {
  let httpClient: HttpClient;
  let http: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()],
    });
    httpClient = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => http.verify());

  it('attaches the in-memory token and retries one unauthorized request after refresh', () => {
    auth.login('admin@example.test', 'password').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush(null);
    http.expectOne('/api/v1/auth/login').flush(session);

    let result: unknown;
    httpClient.get('/api/v1/private').subscribe(value => result = value);
    const first = http.expectOne('/api/v1/private');
    expect(first.request.headers.get('Authorization')).toBe('Bearer initial-access');
    first.flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/auth/csrf').flush(null);
    http.expectOne('/api/v1/auth/refresh').flush({
      ...session,
      accessToken: 'refreshed-access',
    });
    const retry = http.expectOne('/api/v1/private');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer refreshed-access');
    retry.flush({ ok: true });
    expect(result).toEqual({ ok: true });
  });

  it('does not retry auth endpoint failures and navigates to login when refresh fails', () => {
    auth.login('admin@example.test', 'password').subscribe({ error: () => undefined });
    http.expectOne('/api/v1/auth/csrf').flush(null);
    http.expectOne('/api/v1/auth/login').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.token()).toBeNull();

    auth.login('admin@example.test', 'password').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush(null);
    http.expectOne('/api/v1/auth/login').flush(session);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    httpClient.get('/api/v1/private').subscribe({ error: () => undefined });
    http.expectOne('/api/v1/private').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/auth/csrf').flush(null);
    http.expectOne('/api/v1/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  it('passes forbidden responses through without trying to refresh', () => {
    auth.login('admin@example.test', 'password').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush(null);
    http.expectOne('/api/v1/auth/login').flush(session);
    let status = 0;
    httpClient.get('/api/v1/admin').subscribe({ error: error => status = error.status });
    http.expectOne('/api/v1/admin').flush({}, { status: 403, statusText: 'Forbidden' });
    expect(status).toBe(403);
    http.expectNone('/api/v1/auth/refresh');
  });
});
