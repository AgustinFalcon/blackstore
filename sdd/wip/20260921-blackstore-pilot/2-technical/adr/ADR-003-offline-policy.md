# ADR-003 — Offline policy

**Status:** proposed

Offline mode is catalog read-only only, bounded by imported version and valid-until. New sales, stock allocation and offline commit are denied. Those features need a separate consistency design and Sol GO.
