# Sofra v1 — Swing desktop application

This folder keeps the original **SWE312** coursework version of the project: a single-window Java Swing
application with an in-memory order list. It is preserved unchanged for comparison; the current application
lives in the repository root.

![Swing version](../../docs/screenshots/v1-swing.png)

## Run it

```bash
cd legacy/swing-desktop
javac -d out $(find src -name "*.java")
java -cp out com.swe312.restaurant.Main
```

## What changed in v2

| v1 (Swing) | v2 (Spring Boot) |
|---|---|
| One desktop window | Web app for tills, kitchen screens, tablets and phones |
| Orders lost when the app closes | PostgreSQL / H2 with Flyway migrations |
| `double` prices, `Math.round` | `BigDecimal`, half-up to the kuruş, server-side pricing |
| Tax computed in two places | One `PricingCalculator` for quotes and orders |
| Four statuses, no cancellation | Type-aware state machine with audit trail and cancellation |
| No users | JWT authentication with Manager / Waiter / Kitchen roles |
| No tests | 100+ tests, ~95 % coverage, CI |
