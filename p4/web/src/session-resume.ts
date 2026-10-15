// Only server-issued recovery authority is persisted; the login credential stays in memory.
const KEY = 'scrcpy.resume.v2';
const MAX_AGE_MS = 45000;
export interface ResumeSession { url: string; token: string; clientId: string; room: string; expiresAt: number; }
export function readResumeSession(expectedUrl?: string): ResumeSession | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const value = JSON.parse(raw) as ResumeSession;
    if (typeof value.url !== 'string' || !/^wss?:$/.test(new URL(value.url).protocol)
        || typeof value.token !== 'string' || !/^[a-f0-9]{64}$/i.test(value.token)
        || typeof value.clientId !== 'string' || !/^[a-f0-9]{32}$/i.test(value.clientId)
        || typeof value.room !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(value.room)
        || !Number.isFinite(value.expiresAt) || value.expiresAt <= Date.now()
        || value.expiresAt > Date.now() + MAX_AGE_MS) {
      sessionStorage.removeItem(KEY); return null;
    }
    return expectedUrl && value.url !== expectedUrl ? null : value;
  } catch { return null; }
}
export function saveResumeSession(url: string, token: string, clientId: string, room: string): void {
  try { sessionStorage.setItem(KEY, JSON.stringify({ url, token, clientId, room, expiresAt: Date.now() + MAX_AGE_MS })); }
  catch { /* Recovery remains available in memory when storage is disabled. */ }
}
export function forgetResumeSession(url: string): void {
  try { if (readResumeSession()?.url === url) sessionStorage.removeItem(KEY); } catch { /* Storage policy. */ }
}
export function newPeerId(): string {
  const bytes = new Uint8Array(16); crypto.getRandomValues(bytes);
  return Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
}
