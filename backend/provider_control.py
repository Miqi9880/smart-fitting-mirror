"""Internal provider deadlines; no HTTP contract or credentials live here."""
from __future__ import annotations

import time
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Callable


class ProviderFailure(RuntimeError):
    """Safe, user-facing failure without provider payloads or signed URLs."""

    def __init__(self, code: str, message: str, *, retryable: bool = False):
        super().__init__(message)
        self.code, self.message, self.retryable = code, message, retryable


class GenerationBudget:
    def __init__(self, seconds: float, *, clock: Callable[[], float] = time.monotonic):
        self.clock = clock
        self.deadline = clock() + seconds

    @property
    def expired(self) -> bool:
        return self.clock() >= self.deadline

    def remaining(self) -> float:
        remaining = self.deadline - self.clock()
        if remaining <= 0:
            raise ProviderFailure("GENERATION_TIMEOUT", "虚拟试衣超过总等待时限", retryable=True)
        return remaining

    def check(self) -> None:
        self.remaining()

    def capped(self, seconds: float) -> GenerationBudget:
        child = GenerationBudget(seconds, clock=self.clock)
        child.deadline = min(child.deadline, self.deadline)
        return child

    def socket_timeout(self, cap: float = 10.0) -> float:
        # Separate connect/read waits must not each receive the entire budget.
        return min(cap, self.remaining() / 2)


network_budget: ContextVar[GenerationBudget | None] = ContextVar("provider_network_budget", default=None)


@contextmanager
def using_budget(budget: GenerationBudget | None):
    token = network_budget.set(budget)
    try:
        if budget is not None:
            budget.check()
        yield
    finally:
        network_budget.reset(token)
