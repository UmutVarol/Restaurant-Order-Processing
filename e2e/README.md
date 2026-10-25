# Sofra end-to-end tests

[Playwright](https://playwright.dev) tests that drive the real web client against a running Sofra server.

```bash
# from the repository root, start the app with demo data
./mvnw spring-boot:run

# in another terminal
cd e2e
npm install
npx playwright install chromium
npm test
```

The tests reuse a server already listening on `http://localhost:8080` (override with `BASE_URL`). In CI they
start the packaged jar themselves.

| Spec | Covers |
| --- | --- |
| `login.spec.js` | Demo accounts, bad credentials, each role's home screen |
| `service.spec.js` | Till to kitchen over Server-Sent Events, the kitchen workflow, cancelling with a reason |
| `pos.spec.js` | Search and category filters, in-cart state, plurals, the 50-per-line limit, clear and undo, kitchen note, coupons |
| `orders.spec.js` | Filters kept in the URL across reloads, keyboard access to the order drawer |
| `layout.spec.js` | No sideways scrolling on any screen (desktop and phone, light and dark), skip link, theme toggle |
