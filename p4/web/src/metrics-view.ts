/** Attach optional diagnostics without making video layout a startup requirement. */
export function attachMetricsBar(video: HTMLVideoElement, bar: HTMLElement, container?: HTMLElement): boolean {
  if (container) {
    container.append(bar);
    return true;
  }
  // Keep compatibility with embedded players that mount metrics independently.
  const anchor = video.closest('.stage') ?? video;
  if (!anchor.parentElement) return false;
  anchor.before(bar);
  return true;
}
