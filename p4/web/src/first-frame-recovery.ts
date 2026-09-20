// A connected transport does not prove that the receiver rendered a usable IDR.
// Retry capture refresh only while waiting for the first displayed frame.
export class FirstFrameRecovery {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private finished = false;
  private ticks = 0;
  private attempts = 0;
  private lastAttempt = -2;
  constructor(private readonly ready: () => boolean, private readonly request: () => boolean,
      private readonly exhausted: () => void) { }
  start(): void {
    if (this.finished || this.timer !== null) { return; }
    this.timer = setTimeout(() => this.tick(), 1000);
  }
  stop(): void {
    this.finished = true;
    if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
  }
  private tick(): void {
    this.timer = null;
    if (this.finished) { return; }
    ++this.ticks;
    if (this.ticks >= 15 || (this.attempts >= 3 && this.ticks - this.lastAttempt >= 2)) {
      this.stop(); this.exhausted(); return;
    }
    if (this.ready() && this.attempts < 3 && this.ticks - this.lastAttempt >= 2 && this.request()) {
      ++this.attempts;
      this.lastAttempt = this.ticks;
    }
    this.start();
  }
}
