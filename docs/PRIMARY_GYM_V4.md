# Primary gym and membership policy v4

## Scope and implementation plan

The four admin screenshots supplied on 28 October 2026 define a new opt-in membership flow. Existing v1/v2/v3 controllers, request/response DTOs, gRPC contracts and check-in implementations are preserved. No production infrastructure or databases were changed.

1. Inspect catalog gym subscriptions, QR/proximity checks, work hours, order subscriptions/visits, gateway authentication and notifications.
2. Add separate catalog membership policies and DTOs, with package hierarchy, prices, categories and time rules.
3. Add primary-gym selection/history and v4 check-in accounting in order, using subscription row locks and idempotent request IDs.
4. Add pricing snapshots and a read-only monthly settlement calculation.
5. Add a searchable affected-subscriber directory and transactional, localized disable-notification events.
6. Add gateway routes, migrations, regression tests and this API guide; commit feature branches and merge into development.

Affected repositories: catalog-backend, order-backend, gateway-backend, notifications-backend. Each uses `feature/primary-gym-v4`, based on its local `development`. The user, identity and payment services already provide the required directory/authentication APIs; payment-backend has no gym-payout flow, so this feature does not change customer payment processing. Frontend/mobile integration is through the new endpoints below; the screenshots are backend requirements, not a UI rebuild.

## Domain decisions

- Time zone: Asia/Baku. Usage follows subscription-start anniversaries, clamped to subscription expiry; January 31 -> February 28 -> March 31 does not drift. Cycle end is exclusive.
- A package maps to one of BRONZE, SILVER, GOLD, PLATINUM. Use actual plan IDs from the existing subscription-package API; example IDs below are illustrative. Mapping is immutable after policy creation to preserve existing selections.
- Directly included members use their tier's default price. Higher tiers can inherit the lowest included lower tier. An enabled time rule overrides the price tier for every admitted member, including Gold/Platinum and primary-gym members.
- Time rules specify days, inclusive start/end minutes, admitted tiers and price tier. Rules cannot overlap and must exactly cover current gym work hours. Split overnight time rules into two day ranges. General gym work hours may cross midnight; the next day's early segment is recognized. Closed/rest days deny entry.
- General allowance: 12 confirmed visits in the subscription month, including primary-gym visits. A primary gym permits up to 30 visits in that month, one visit per Baku calendar day. Repeat general scans of the same gym within two hours are denied. Different gyms may be visited on the same day while the general allowance remains.
- Primary gym can be selected on the subscription screen, by an admin, or on first eligible v4 QR entry. Changing it after primary usage in the current month is forbidden. A successful entry at another gym clears it and counts toward the general allowance. A denied check-in does not clear it. Previous primary usage prevents automatic reselection in that same month.
- Disabling primary selection immediately blocks new selections. Existing selections keep access until the end of the subscription month containing the disable event. An append-only disable history prevents repeated toggles/re-enabling from reviving old selections. Expired selection history is finalized lazily by the v4 status API/check-in flow.
- Amounts use decimal AZN snapshots. Up to 12 primary visits: sum entry prices. From visit 13: flat monthly payout instead of per-entry pricing, provided the selection lasted through the cycle. When time-based monthly prices differ, **visit 13's rate determines the flat amount**. This is an explicit interpretation of an ambiguity in the screenshots.
- Settlement is a calculation, not a money transfer. It is available only after cycle end. Cycles containing older unpriced visits require reconciliation and return 409 rather than inventing historical prices.
- Older scan endpoints retain their behavior. Clients adopting primary-gym entitlements must use v4 consistently. Earlier visits are included in v4 counters, but older scan implementations do not manage v4 primary-selection transitions or price snapshots. Reservation/frozen-session scanning remains on its existing flow; this v4 endpoint is for membership gym entry.

## API ownership and authorization

All public routes pass through the existing gateway authentication/header sanitization. Admin endpoints require ADMIN. Personal endpoints derive the user ID from authentication, never the body.

| Method | Path | Owner |
|---|---|---|
| GET | `/api/v4/admin/gyms/{gymId}/membership-policy` | catalog |
| PUT | `/api/v4/admin/gyms/{gymId}/membership-policy` | catalog |
| GET | `/api/v4/admin/gyms/{gymId}/primary-subscribers?q=&page=0&size=20` | catalog |
| GET | `/api/v4/gyms/{gymId}/membership-quote?packageId={id}` | catalog |
| POST | `/api/v4/gyms/entrance/scan` | catalog |
| GET | `/api/v4/me/subscriptions/primary-gym` | order |
| PUT | `/api/v4/me/subscriptions/primary-gym` | order |
| GET | `/api/v4/admin/users/{userId}/primary-gym` | order |
| PUT | `/api/v4/admin/users/{userId}/primary-gym` | order |
| GET | `/api/v4/admin/gyms/{gymId}/settlements/{subscriptionId}?cycleStart=2026-10-05T00:00:00` | order |

The affected-subscriber page contains `content`, `total`, `page`, `size`; each row includes user ID, full name, phone, subscription/package IDs, package name, selection/expiry timestamps and cycle visit count. Search matches name or phone; page size is 1..100. It composes existing user/package gRPC APIs and does not duplicate their source records.

Status includes subscription/package IDs, cycle start/end, current gym/name/source/selection/expiry, primary/general used and limits, `canChange`, complete selection history and `accessibleHours` with days/times/price tiers. Quotes include current access/denial reason, primary eligibility, prices, policy version, disable history and all permitted windows. Quote `allowed` concerns entry now; `primaryEnabled` concerns selection availability, so a closed gym can still be selected.

Private order paths are NOT routed by the gateway:

- `POST /api/v4/internal/primary-gym/check-ins`
- `GET /api/v4/internal/primary-gym/gyms/{gymId}/subscribers`

Both require an authenticated forwarded identity and `X-Primary-Gym-Key`, validated in constant time. The existing private service network/header trust boundary still applies; do not expose service ports publicly.

## Save policy

Initial `policy.version` is 0. Subsequent updates must send the version returned by GET/PUT. Stale versions return 409. `primaryDisabledAt` is server-controlled; callers cannot set grandfathering dates. All four package rows are required. Categories are independent per-tier labels.

```json
{
  "policy": {
    "version": 0,
    "timeRulesEnabled": true,
    "packages": [
      {"packageId":1,"tier":"BRONZE","included":true,"primaryEnabled":true,"entryPrice":4.00,"primaryMonthlyPrice":50.00,"categories":["Fitness zalı","Hovuz"]},
      {"packageId":2,"tier":"SILVER","included":true,"primaryEnabled":true,"entryPrice":6.00,"primaryMonthlyPrice":60.00,"categories":["Fitness zalı","Hovuz","Hamam"]},
      {"packageId":3,"tier":"GOLD","included":false,"primaryEnabled":false,"entryPrice":0,"categories":[]},
      {"packageId":4,"tier":"PLATINUM","included":false,"primaryEnabled":false,"entryPrice":0,"categories":[]}
    ],
    "timeRules": [
      {"days":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"],"start":"00:01","end":"16:59","allowedTiers":["BRONZE","SILVER","GOLD","PLATINUM"],"priceTier":"BRONZE"},
      {"days":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"],"start":"17:00","end":"23:59","allowedTiers":["SILVER","GOLD","PLATINUM"],"priceTier":"SILVER"}
    ]
  },
  "notifySubscribers": false
}
```

The example requires matching gym work hours of 00:01..23:59 every day. When time rules are disabled, send `timeRulesEnabled:false` and `timeRules:[]`; current gym work hours still govern access. A newly configured policy is separate from legacy gym subscription rows; GET returns 404 until explicitly configured.

To disable primary selection, keep inclusion/prices and set the affected row's `primaryEnabled:false`. Existing monthly prices are retained if omitted during disable so current-month access remains priced. If `notifySubscribers:true`, supply all three translations:

```json
"notification": {
  "AZ":{"title":"Əsas zal seçimi dayandırılır","body":"Cari abunəlik ayının sonunadək əsas zal imkanın davam edir."},
  "EN":{"title":"Primary gym selection is closing","body":"Your primary gym access continues until this subscription month ends."},
  "RU":{"title":"Выбор основного зала закрывается","body":"Доступ к основному залу сохранится до конца текущего месяца подписки."}
}
```

Titles are required, max 65 characters; bodies required, max 240. Notification recipients are snapshotted from currently affected subscribers and their packages; a directory failure prevents a notified save from silently omitting recipients. Policy, disable-history and outbox writes share one catalog transaction. Notifications choose each user's preferred language through the existing identity directory (AZ fallback), with completed-recipient deduplication. Delivery follows existing notification eligibility/device behavior. Delivery is at least once: a process crash between sending and recording completion can duplicate a push.

## Selection and QR entry

PUT either selection path with `{"gymId":10}`. A successful same-gym selection is idempotent. A stale/used/disabled selection returns 409.

POST scan with:

```json
{"gymId":10,"qrCodeValue":"stored-gym-qr-value","lat":40.4093,"lng":49.8671,"requestId":"client-generated-unique-uuid"}
```

The QR must match the gym's stored QR value/token; location is mandatory, finite and within 200 metres. Use the existing generated QR contents (currently numeric gym IDs); the service compares against stored data. A catalog outage/order timeout fails closed. Reuse the same request ID for retries of the same check-in; do not generate a new ID on a network retry. Reusing it for a different gym returns 409. Successful receipt fields: `id`, `gymId`, `checkedInAt`, `primary`, `generalVisits`, `primaryVisits`, `entryPrice`, `monthlyPrice`, `priceTier`.

New v4 business errors retain their HTTP status through scoped advice, e.g. `{"error":{"code":"GENERAL_VISIT_LIMIT","status":409}}`. Other useful codes: `PRIMARY_VISIT_LIMIT`, `ALREADY_ENTERED_TODAY`, `ALREADY_SCANNED_RECENTLY`, `PRIMARY_ALREADY_USED_THIS_MONTH`, `PRIMARY_SELECTION_DISABLED`, `TIME_ACCESS_DENIED`, `GYM_CLOSED`, `POLICY_CHANGED`, `TIME_RULES_MUST_COVER_WORK_HOURS`. Legacy error handlers/endpoints are unchanged.

## Deployment and validation

1. Provision the same nonempty `PRIMARY_GYM_INTERNAL_KEY` / `primary-gym.internal-key` in catalog and order through the normal secret configuration. No key is committed. Private operations fail closed when it is absent.
2. Configure `catalog.backend.url` in order and `order.backend.url` in catalog if service DNS differs from defaults. HTTP clients have bounded connection/read timeouts.
3. Review/apply each affected service's `V100` SQL using the established migration process. Catalog/notifications currently rely on Hibernate schema update; do not assume adding a SQL file enables Flyway there. Explicitly apply the supplied PostgreSQL indexes/constraints as part of rollout. Never run them against production without deployment authorization.
4. Provision Kafka topic `primary-gym-disabled-v4` (and its dead-letter topic according to existing Kafka policy), publish/consume permissions, and monitor catalog pending outbox rows and consumer failures.
5. Deploy notifications/order/catalog/gateway together, configure gym policies, then opt clients into v4. Existing endpoint callers remain on legacy rules.

Regression coverage includes month-end anniversaries, cycle expiry, 12/13/30/31 boundaries, first-QR selection, primary/general entry, same-day other-gym transitions, retries, reused IDs, forbidden changes, disable grace, repeated toggles, time-price inheritance, 16:59/17:00 boundaries, overlap rules, overnight hours, QR proximity units, internal service-key rejection, preserved error status, translated delivery/deduplication and public/private gateway routing. Full service test suites were run in isolated feature worktrees. PostgreSQL/Kafka/live-device integration was not executed: the local Docker daemon was unavailable. No runtime deployment, push notification or bank transfer was performed.

Final verification: catalog 10, order 151, gateway 154, notifications 1 tests; 316 total, all passing, none skipped. Of these, 32 are new v4 regressions. Run `./gradlew test --console=plain` in each service.
