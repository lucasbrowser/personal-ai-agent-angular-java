import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { firstValueFrom, Observable, of } from 'rxjs';
import { AuthService } from './auth.service';
import { authGuard } from './auth.guard';

describe('authGuard', () => {
  it('allows a route when a token is already in memory', () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: { token: () => 'access' } }],
    });
    expect(TestBed.runInInjectionContext(() => authGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot))).toBe(true);
  });

  it('redirects to login when session renewal fails', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: { token: () => null, refresh: () => of(false) } }],
    });
    const guardResult = TestBed.runInInjectionContext(() => authGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot));
    const result = await firstValueFrom(guardResult as Observable<boolean | UrlTree>);
    const router = TestBed.inject(Router);
    expect(result).toEqual(router.parseUrl('/login'));
  });
});
