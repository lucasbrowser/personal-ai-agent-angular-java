import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { AuthResponse } from './auth.models';

const response: AuthResponse = {
  accessToken: 'access-in-memory',
  user: { id: 'user-1', name: 'Admin', email: 'admin@example.test', role: 'ADMIN' },
};

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('keeps the access token in memory after login and clears it on logout', () => {
    let completed = false;
    auth.login('admin@example.test', 'password').subscribe(() => completed = true);
    const csrf = http.expectOne('/api/v1/auth/csrf');
    expect(csrf.request.withCredentials).toBe(true);
    csrf.flush(null);
    const login = http.expectOne('/api/v1/auth/login');
    expect(login.request.withCredentials).toBe(true);
    login.flush(response);

    expect(completed).toBe(true);
    expect(auth.token()).toBe('access-in-memory');
    expect(auth.user()?.email).toBe('admin@example.test');

    auth.logout().subscribe();
    http.expectOne('/api/v1/auth/csrf').flush(null);
    http.expectOne('/api/v1/auth/logout').flush(null);
    expect(auth.token()).toBeNull();
    expect(auth.user()).toBeNull();
  });

  it('shares one refresh request between simultaneous callers and clears expired sessions', () => {
    auth.login('admin@example.test', 'password').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush(null);
    http.expectOne('/api/v1/auth/login').flush(response);

    const results: boolean[] = [];
    auth.refresh().subscribe(value => results.push(value));
    auth.refresh().subscribe(value => results.push(value));
    http.expectOne('/api/v1/auth/csrf').flush(null);
    http.expectOne('/api/v1/auth/refresh').flush(response, { status: 401, statusText: 'Unauthorized' });

    expect(results).toEqual([false, false]);
    expect(auth.token()).toBeNull();
    expect(auth.user()).toBeNull();
  });
});
