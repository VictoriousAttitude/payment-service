from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

# ISO 4217 minor-unit exponents. Most currencies are 2 (cents); a handful are
# 0 (no minor unit) or 3 (mils). Anything not listed defaults to 2.
_EXPONENTS: dict[str, int] = {
    "EUR": 2,
    "USD": 2,
    "GBP": 2,
    "CHF": 2,
    "JPY": 0,
    "KRW": 0,
    "BHD": 3,
    "KWD": 3,
    "TND": 3,
}
_DEFAULT_EXPONENT = 2


def exponent_of(currency: str) -> int:
    return _EXPONENTS.get(currency, _DEFAULT_EXPONENT)


@dataclass(frozen=True, slots=True)
class Money:
    """An exact monetary amount in integer minor units of a single currency.

    Money never touches float. Decimal appears only at the parse boundary
    (`from_decimal`); the core stores and compares integers, so rounding can
    happen exactly once - deliberately - when a value enters the system.
    Equality includes the currency, so a cross-currency comparison is simply
    `False`, never a silent unit error.
    """

    minor: int
    currency: str

    @classmethod
    def from_minor(cls, minor: int, currency: str) -> Money:
        return cls(minor, currency)

    @classmethod
    def from_decimal(cls, value: Decimal, currency: str) -> Money:
        scaled = value.scaleb(exponent_of(currency))
        if scaled != scaled.to_integral_value():
            raise ValueError(
                f"{value} has finer precision than {currency} minor units allow"
            )
        return cls(int(scaled), currency)

    def to_decimal(self) -> Decimal:
        return Decimal(self.minor).scaleb(-exponent_of(self.currency))

    def _require_same_currency(self, other: Money) -> None:
        if self.currency != other.currency:
            raise ValueError(
                f"cross-currency operation: {self.currency} vs {other.currency}"
            )

    def __add__(self, other: Money) -> Money:
        self._require_same_currency(other)
        return Money(self.minor + other.minor, self.currency)

    def __sub__(self, other: Money) -> Money:
        self._require_same_currency(other)
        return Money(self.minor - other.minor, self.currency)

    def __neg__(self) -> Money:
        return Money(-self.minor, self.currency)

    def __lt__(self, other: Money) -> bool:
        self._require_same_currency(other)
        return self.minor < other.minor

    def __str__(self) -> str:
        return f"{self.to_decimal()} {self.currency}"
