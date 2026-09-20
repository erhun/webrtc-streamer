// Tab-scoped recovery credentials survive reload without persisting the login token.
const KEY = 'scrcpy.resume.v1';
const MAX_AGE_MS = 45000;
export interface ResumeSession { url: string; token: string; expiresAt: number; }
export function readResumeSession(): ResumeSession | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) { return null; }
    const value = JSON.parse(raw) as ResumeSession;
    if (typeof value.url !== 'string' || !/^wss?:$/.test(new URL(value.url).protocol)
        || typeof value.token !== 'string' || value.token.length !== 64
        || !Number.isFinite(value.expiresAt) || value.expiresAt <= Date.now()
        || value.expiresAt > Date.now() + MAX_AGE_MS) {
      sessionStorage.removeItem(KEY); return null;
    }
    return value;
  } catch { return null; } // Disabled storage must not break an ordinary connection.
}
export function saveResumeSession(url: string, token: string): void {
  try { sessionStorage.setItem(KEY, JSON.stringify({ url, token, expiresAt: Date.now() + MAX_AGE_MS })); }
  catch { /* Private browsing/storage policy: in-memory weak-network recovery still works. */ }
}
export function forgetResumeSession(url: string): void {
  try { if (readResumeSession()?.url === url) { sessionStorage.removeItem(KEY); } }
  catch { /* Storage may be disabled. */ }
}
export function newPeerId(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
}
