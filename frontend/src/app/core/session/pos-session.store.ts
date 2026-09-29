import { Injectable, signal } from '@angular/core';
import { StaffRole } from './staff-role';

const ACTOR_KEY = 'blackstore.pos.actor';

export interface PosActor {
  readonly actorId: number;
  readonly role: StaffRole;
}

/**
 * BlackStore has no login HTTP on :8081. This store is the console actor
 * behind X-Actor-Id and X-Role. It does not create users and it does not
 * call StoreCore.
 */
@Injectable({ providedIn: 'root' })
export class PosSessionStore {
  readonly actor = signal<PosActor | null>(readActor());
  readonly denial = signal<string | null>(null);

  signIn(actorId: number, role: StaffRole): void {
    const actor: PosActor = { actorId, role };
    this.actor.set(actor);
    sessionStorage.setItem(ACTOR_KEY, JSON.stringify(actor));
    this.denial.set(null);
  }

  signOut(): void {
    this.actor.set(null);
    sessionStorage.removeItem(ACTOR_KEY);
  }

  deny(message: string): void {
    this.denial.set(message);
  }

  clearDenial(): void {
    this.denial.set(null);
  }
}

function readActor(): PosActor | null {
  try {
    const raw = sessionStorage.getItem(ACTOR_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { actorId?: unknown; role?: unknown };
    const actorId = typeof parsed.actorId === 'number' ? parsed.actorId : Number(parsed.actorId);
    if (!Number.isInteger(actorId) || actorId <= 0) return null;
    const role = StaffRole.fromWire(parsed.role);
    if (role === StaffRole.Unknown) return null;
    return { actorId, role };
  } catch {
    return null;
  }
}
