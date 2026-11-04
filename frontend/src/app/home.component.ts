import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from './auth/auth.service';
import { VoiceAssistantComponent } from './voice/voice-assistant.component';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [VoiceAssistantComponent],
  template: `
    <main class="home">
      <header><span class="orb"></span><span>JARVIS</span><button (click)="logout()">Sair</button></header>
      <section class="welcome"><p>SESSÃO SEGURA</p><h1>Olá, {{ auth.user()?.name }}.</h1><p>Seu espaço pessoal está pronto.</p></section>
      <app-voice-assistant />
    </main>
  `,
  styles: [`
    .home { min-height: 100vh; background: radial-gradient(ellipse at 50% 40%, #142544, #080d17 70%); color: #edf3ff; padding: 28px 6vw 64px; box-sizing: border-box; }
    header { display: flex; align-items: center; gap: 12px; letter-spacing: .2em; font-size: 12px; color: #9edaff; }
    .orb { width: 16px; height: 16px; border: 1px solid #83d4ff; border-radius: 50%; box-shadow: 0 0 18px #50baff; }
    header button { margin-left: auto; background: transparent; color: #b9c9dd; border: 1px solid #52627a; border-radius: 8px; padding: 9px 15px; cursor: pointer; }
    .welcome { max-width: 900px; margin: 10vh auto 36px; text-align: center; }
    .welcome p { color: #91a4bc; }
    .welcome h1 { font-size: clamp(34px, 7vw, 64px); letter-spacing: -.05em; margin: 8px 0; }
  `],
})
export class HomeComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  logout(): void { this.auth.logout().subscribe(() => void this.router.navigate(['/login'])); }
}
