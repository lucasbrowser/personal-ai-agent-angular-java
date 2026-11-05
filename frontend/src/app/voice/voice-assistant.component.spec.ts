import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { VoiceEventsService } from './voice-events.service';
import { VoiceAssistantComponent } from './voice-assistant.component';
import { VoiceWebRTCService } from './voice-webrtc.service';

describe('VoiceAssistantComponent', () => {
  let fixture: ComponentFixture<VoiceAssistantComponent>;
  let component: VoiceAssistantComponent;
  let events: VoiceEventsService;
  let connectionEvents: Subject<'connected' | 'failed'>;
  let start: ReturnType<typeof vi.fn>;
  let stop: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    connectionEvents = new Subject();
    start = vi.fn(async (onRequest: () => void, onGranted: () => void) => { onRequest(); onGranted(); });
    stop = vi.fn();
    TestBed.configureTestingModule({
      imports: [VoiceAssistantComponent],
      providers: [
        VoiceEventsService,
        { provide: VoiceWebRTCService, useValue: { connectionEvents, start, stop } },
      ],
    });
    fixture = TestBed.createComponent(VoiceAssistantComponent);
    component = fixture.componentInstance;
    events = TestBed.inject(VoiceEventsService);
    fixture.detectChanges();
  });

  it('moves through microphone, connection, listening, thinking and speaking states', async () => {
    const startButton = fixture.nativeElement.querySelector('.start-button') as HTMLButtonElement;
    startButton.click();
    expect(component.state()).toBe('CONNECTING');
    await fixture.whenStable();
    expect(component.state()).toBe('LISTENING');

    events.handleMessage(JSON.stringify({ type: 'input_audio_buffer.speech_stopped' }));
    expect(component.state()).toBe('THINKING');
    events.handleMessage(JSON.stringify({ type: 'response.output_audio.delta', delta: 'chunk' }));
    expect(component.state()).toBe('SPEAKING');
    events.handleMessage(JSON.stringify({ type: 'input_audio_buffer.speech_started' }));
    expect(component.state()).toBe('LISTENING');
  });

  it('shows transcripts, allows ending the conversation, and releases the connection', async () => {
    await component.start();
    events.publishTranscript('user', 'Olá');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Você: Olá');

    component.stop();
    fixture.detectChanges();
    expect(stop).toHaveBeenCalled();
    expect(component.state()).toBe('IDLE');
    expect(fixture.nativeElement.querySelector('.start-button')).toBeTruthy();
  });

  it('prevents duplicate starts and reports microphone permission denial', async () => {
    start.mockImplementationOnce((onRequest: () => void) => { onRequest(); return Promise.resolve(); });
    await component.start();
    await component.start();
    expect(start).toHaveBeenCalledTimes(1);

    start.mockImplementationOnce((onRequest: () => void) => { onRequest(); return Promise.reject(new DOMException('denied', 'NotAllowedError')); });
    component.stop();
    await component.start();
    expect(component.state()).toBe('ERROR');
    expect(component.error()).toContain('Permita o acesso ao microfone');
    expect(stop).toHaveBeenCalled();
  });

  it('cleans up when the data channel or peer connection fails', () => {
    connectionEvents.next('failed');
    expect(component.state()).toBe('ERROR');
    expect(component.error()).toContain('interrompida');
    expect(stop).toHaveBeenCalled();
  });
});
