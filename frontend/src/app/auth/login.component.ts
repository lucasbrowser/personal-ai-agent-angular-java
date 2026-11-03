import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from './auth.service';

@Component({ selector: 'app-login', imports: [ReactiveFormsModule], templateUrl: './login.component.html', styleUrl: './login.component.scss' })
export class LoginComponent {
  readonly loading = signal(false); readonly error = signal('');
  readonly form = inject(FormBuilder).nonNullable.group({ email: ['', [Validators.required, Validators.email]], password: ['', Validators.required] });
  constructor(private readonly auth: AuthService, private readonly router: Router) {}
  submit(): void {
    if (this.form.invalid || this.loading()) { this.form.markAllAsTouched(); return; }
    this.loading.set(true); this.error.set('');
    this.auth.login(this.form.getRawValue().email, this.form.getRawValue().password).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: () => { void this.router.navigate(['/']); },
      error: () => this.error.set('Não foi possível autenticar. Confira seu e-mail e sua senha.'),
    });
  }
}
