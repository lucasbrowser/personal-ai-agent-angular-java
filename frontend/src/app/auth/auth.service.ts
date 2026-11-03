import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, finalize, map, of, shareReplay, switchMap, tap } from 'rxjs';
import { AuthResponse, AuthUser } from './auth.models';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = '/api/v1/auth';
  private accessToken: string | null = null;
  private refreshRequest?: Observable<boolean>;
  readonly user = signal<AuthUser | null>(null);
  constructor(private readonly http: HttpClient) {}

  login(email: string, password: string): Observable<void> {
    return this.http.get<void>(`${this.api}/csrf`, { withCredentials: true }).pipe(
      switchMap(() => this.http.post<AuthResponse>(`${this.api}/login`, { email, password }, { withCredentials: true })), tap(r => this.accept(r)), map(() => undefined));
  }
  token(): string | null { return this.accessToken; }
  refresh(): Observable<boolean> {
    if (!this.refreshRequest) {
      this.refreshRequest = this.http.get<void>(`${this.api}/csrf`, { withCredentials: true }).pipe(
        switchMap(() => this.http.post<AuthResponse>(`${this.api}/refresh`, {}, { withCredentials: true })),
        tap(r => this.accept(r)), map(() => true), catchError(() => { this.clear(); return of(false); }),
        finalize(() => this.refreshRequest = undefined), shareReplay({ bufferSize: 1, refCount: false }),
      );
    }
    return this.refreshRequest;
  }
  logout(): Observable<void> {
    return this.http.get<void>(`${this.api}/csrf`, { withCredentials: true }).pipe(
      switchMap(() => this.http.post<void>(`${this.api}/logout`, {}, { withCredentials: true })), catchError(() => of(undefined)), tap(() => this.clear()));
  }
  clear(): void { this.accessToken = null; this.user.set(null); }
  private accept(response: AuthResponse): void { this.accessToken = response.accessToken; this.user.set(response.user); }
}
