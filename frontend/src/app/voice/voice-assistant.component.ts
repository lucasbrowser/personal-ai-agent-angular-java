import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { VoiceEventsService } from './voice-events.service';
import { VoiceWebRTCService } from './voice-webrtc.service';
import { VoiceState } from './voice.models';

@Component({
  selector: 'app-voice-assistant',
  standalone: true,
  templateUrl: './voice-assistant.component.html',
  styleUrl: './voice-assistant.component.scss',
})
export class VoiceAssistantComponent {
  private readonly destroyRef = inject(DestroyRef);
  private readonly webRtc = inject(VoiceWebRTCService);
  readonly state = signal<VoiceState>('IDLE');
  readonly error = signal('');
  readonly transcript = signal<string[]>([]);

  constructor() {
    inject(VoiceEventsService).events$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(event => {
      if (event.kind === 'state') this.state.set(event.state);
      else if (event.kind === 'error') this.fail(event.message);
      else this.transcript.update(lines => [...lines, `${event.role === 'user' ? 'Você' : 'Jarvis'}: ${event.text}`].slice(-100));
    });
    this.webRtc.connectionEvents.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(event => {
      if (event === 'connected') this.state.set('LISTENING');
      else this.fail('A conexão de voz foi interrompida.');
    });
    this.destroyRef.onDestroy(() => this.webRtc.stop());
  }

  async start(): Promise<void> {
    if (this.state() !== 'IDLE' && this.state() !== 'ERROR') return;
    this.error.set('');
    this.transcript.set([]);
    try {
      await this.webRtc.start(
        () => this.state.set('REQUESTING_MICROPHONE'),
        () => this.state.set('CONNECTING'),
      );
      if (this.state() === 'CONNECTING') this.state.set('LISTENING');
    } catch (error) {
      const message = error instanceof Error ? error.message : '';
      const denied = error instanceof DOMException && (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError');
      this.fail(denied ? 'Permita o acesso ao microfone para conversar com o Jarvis.' : message || 'Não foi possível iniciar a conversa por voz.');
    }
  }

  stop(): void {
    this.state.set('DISCONNECTING');
    this.webRtc.stop();
    this.state.set('IDLE');
  }

  private fail(message: string): void {
    this.webRtc.stop();
    this.error.set(message);
    this.state.set('ERROR');
  }
}
