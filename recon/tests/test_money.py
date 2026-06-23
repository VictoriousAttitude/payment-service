from decimal import Decimal

import pytest

from recon.domain.money import Money, exponent_of


def test_exponent_of_known_currencies() -> None:
    assert exponent_of("EUR") == 2
    assert exponent_of("JPY") == 0
    assert exponent_of("BHD") == 3


def test_exponent_of_unlisted_currency_defaults_to_two() -> None:
    assert exponent_of("XTS") == 2


def test_from_decimal_unlisted_currency_uses_default_exponent() -> None:
    assert Money.from_decimal(Decimal("1.23"), "XTS") == Money(123, "XTS")


def test_from_decimal_two_exponent() -> None:
    assert Money.from_decimal(Decimal("100.00"), "EUR") == Money(10000, "EUR")


def test_from_decimal_zero_exponent() -> None:
    assert Money.from_decimal(Decimal("100"), "JPY") == Money(100, "JPY")


def test_from_decimal_three_exponent() -> None:
    assert Money.from_decimal(Decimal("1.234"), "BHD") == Money(1234, "BHD")


def test_from_decimal_rejects_finer_precision() -> None:
    with pytest.raises(ValueError):
        Money.from_decimal(Decimal("1.234"), "EUR")


def test_to_decimal_round_trip() -> None:
    money = Money(10000, "EUR")
    assert Money.from_decimal(money.to_decimal(), "EUR") == money


def test_arithmetic_same_currency() -> None:
    assert Money(10000, "EUR") - Money(3000, "EUR") == Money(7000, "EUR")
    assert -Money(3000, "EUR") == Money(-3000, "EUR")


def test_cross_currency_arithmetic_raises() -> None:
    with pytest.raises(ValueError):
        Money(100, "EUR") + Money(100, "USD")


def test_cross_currency_equality_is_false() -> None:
    assert Money(100, "EUR") != Money(100, "USD")
