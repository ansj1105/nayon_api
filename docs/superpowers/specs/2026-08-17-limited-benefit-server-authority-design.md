# Limited Benefit Server Authority Design

## Decision

- Preserve the existing 24-step `기간 한정 혜택` layout and order.
- Reset each account's progress every day at `00:00 Asia/Seoul`.
- The server owns campaign time, unlock order, claim state, purchase/ad proof,
  reward selection, and ledger writes. Unity renders responses and never grants
  these rewards locally.
- Prices come from Google Play. Reward definitions are versioned in PostgreSQL
  so price and reward changes do not require a Unity release.

## Considered approaches

1. **Server-authoritative daily campaign (selected).** Store definitions and
   daily account progress in PostgreSQL; require Google Play receipts or AdMob
   SSV proof for paid/ad rows. This preserves the product flow and closes device
   clock and `PlayerPrefs` manipulation.
2. Hybrid local/server state. This is smaller but still lets clients mint free
   or ad rewards and lets devices disagree, so it is rejected.
3. One campaign-lifetime progression. This is secure but changes the current
   daily-reset product behavior, so it is rejected.

## Scope and stages

The feature is one user flow but has three independently verifiable trust
boundaries. Delivery is split without exposing an insecure fallback:

1. campaign catalog, daily progression, and free claims;
2. paid offer fulfillment through the existing Google Play verifier;
3. rewarded-ad fulfillment through verified AdMob SSV callbacks.

An offer whose proof provider is not configured is returned as
`PROVIDER_UNAVAILABLE`; Unity disables it and does not advance the sequence.

## PostgreSQL model

Migration `V11__create_limited_benefit_campaigns.sql` and matching rollback
`U11__drop_limited_benefit_campaigns.sql` add the campaign tables and indexes,
plus one additive fulfillment discriminator on `store_product_versions`.

### `limited_benefit_campaign_versions`

- `id`, immutable `version`, `campaign_code`, `zone_id`
- `valid_from`, `valid_until`, `active`
- only one active version for `daily_limited_benefit`
- `zone_id` is fixed to `Asia/Seoul` for version 1

### `limited_benefit_offers`

- composite owner: `campaign_version_id`
- stable `offer_code`, `display_order`, title
- `fulfillment_type`: `FREE`, `GOOGLE_PLAY`, or `ADMOB_SSV`
- optional stable `store_offer_id` for paid rows; the active Play product ID is resolved through `store_products`
- optional configured AdMob ad-unit identifier for ad rows
- unique `(campaign_version_id, offer_code)` and display order
- the previous display-order row is the only unlock prerequisite

### `limited_benefit_offer_rewards`

- ordered reward rows owned by one offer
- `reward_type`: `CURRENCY`, `ITEM`, or `EQUIPMENT_BOX`
- supported codes:
  - currencies: `DIAMOND`, `GOLD`
  - items: `SILVER_KEY`, `GOLD_KEY`, `RANDOM_SCROLL`
  - boxes: `ADVANCED_BOX`, `ALL_BOX`
- positive amount and maximum three reward rows per offer

Box fulfillment never trusts a client-side quantity. `ADVANCED_BOX` and
`ALL_BOX` are the game's existing unopened inventory items, so the claim
transaction credits their canonical `player_items` codes. Opening a box and
selecting equipment remains a separate server-authoritative operation.

### `player_limited_benefit_claims`

- `id`, `account_id`, `campaign_version_id`, `offer_id`
- `cycle_date` is the KST date calculated by the server
- `request_id`, request hash, proof type/reference, response JSON, `claimed_at`
- unique `(account_id, campaign_version_id, cycle_date, offer_id)`
- unique `request_id`
- paid proof references one account-owned `GRANTED` store receipt
- response JSON makes same-key retries exact replays

### `limited_benefit_ad_sessions` and `admob_reward_callbacks`

- an authenticated account creates a short-lived session for one unlocked ad
  offer and receives an opaque session UUID for AdMob `custom_data`
- callback `transaction_id` is globally unique
- accepted raw query, key ID, account/session correlation, and timestamps are
  retained for audit; rejected callbacks return an error and never affect economy
- a verified callback marks the session `VERIFIED`; only then may the claim
  transaction grant the offer

## Daily cycle and progression

- `cycleDate = LocalDate.now(ZoneId.of("Asia/Seoul"))` on the server.
- The response includes `serverTime`, `cycleDate`, and `resetsAt` so Unity never
  derives eligibility from the device clock.
- Row 0 is unlocked. Row N is unlocked only when row N-1 has a successful claim
  for the same account, campaign version, and cycle date.
- Claims from an expired cycle return `LIMITED_BENEFIT_CYCLE_EXPIRED` and never
  write a ledger entry.
- Account and request advisory locks plus the claim unique constraints serialize
  concurrent attempts.

## API contract

All account endpoints require the existing Cognito JWT.

### `GET /api/v1/events/limited-benefits/current`

Returns campaign metadata and all 24 offers with:

- server-owned title, order, fulfillment type, rewards, and store product ID;
- `LOCKED`, `AVAILABLE`, `PROVIDER_UNAVAILABLE`, or `CLAIMED` state;
- Google Play localized price remains a Unity IAP value, not a server constant;
- paid/ad proof state where applicable.

No active campaign returns `204`.

### `POST /api/v1/events/limited-benefits/offers/{offerCode}/claims`

- required `Idempotency-Key` UUID header;
- body is empty for `FREE`;
- paid body contains the account-owned `receiptId` returned by the existing
  Google Play verification endpoint;
- ad body contains the verified `adSessionId`;
- returns the claim, granted reward instances, and authoritative economy
  snapshot;
- errors: `404` campaign/offer, `409` locked/claimed/cycle conflict,
  `422` missing or mismatched proof, `503` proof provider unavailable.

### `POST /api/v1/events/limited-benefits/offers/{offerCode}/ad-sessions`

Creates a session only for the currently unlocked `ADMOB_SSV` offer. The
response supplies `sessionId`, `customData`, `userId`, and expiry for Unity's
`ServerSideVerificationOptions`.

### `GET /api/v1/public/admob/rewarded-callback`

- preserves the original query order and bytes;
- verifies the ECDSA signature and `key_id` using Google's AdMob verifier keys;
- checks configured ad unit, reward item/amount, session expiry, account, offer,
  and globally unique `transaction_id`;
- records duplicates as successful replays without a second reward;
- callback verification never grants economy directly; the authenticated claim
  endpoint performs the atomic grant.

The SSV design follows Google's official validation contract:
<https://developers.google.com/admob/android/ssv>.

## Paid purchase flow

1. GET returns a paid offer with its mapped Google product ID.
2. Unity obtains the localized Play price and performs the purchase.
3. Unity sends the token to existing
   `POST /api/v1/store/purchases/google-play/verify`.
4. A limited-benefit product version is marked as event fulfillment and does
   not grant a standalone diamond reward.
5. Unity sends the returned `receiptId` to the limited-benefit claim endpoint.
6. The server locks the account, verifies receipt ownership/product/cycle and
   grants the versioned bundle exactly once.

This requires an additive store-product fulfillment discriminator; existing
diamond products remain `DIRECT_CURRENCY` and are backward compatible.

## Unity behavior

- Replace `GetLimitedBenefitOffers()` hard-coded economic data with the GET
  response while preserving the existing prefabs, row order, labels, and popup
  structure.
- Remove all `NyaonLimitedBenefit*` `PlayerPrefs` eligibility keys and every
  `GrantLimitedBenefitMaterial` call from this flow.
- FREE invokes claim directly.
- GOOGLE_PLAY reuses `NayonStoreRuntime`, then claims with the receipt UUID.
- ADMOB_SSV creates a session, assigns `userId/customData`, shows the rewarded
  ad, polls/refetches until the server callback is verified, then claims.
- Apply only the returned authoritative economy/item snapshot. Cache state
  is account-scoped and request-generation guarded.
- Network failure leaves the offer unclaimed and retryable with the same key.

## Security and failure behavior

- Device time, locale, `PlayerPrefs`, labels, and displayed price are never
  authorization inputs.
- No proof means no paid/ad reward. There is no client-success fallback.
- Every grant, including all reward rows and equipment instances, is one DB
  transaction followed by durable replay serialization.
- A claim that partially fails rolls back completely.
- Account locks, exact replay, and one-live-session reuse bound authenticated
  writes. Before production provider activation, ingress rate limits must also
  cover catalog, claim, ad-session creation, and the public callback.

## Tests and release gates

- V11 forward/rollback verification, ownership constraints, duplicate claim,
  cross-account receipt/session rejection, and cycle-boundary tests.
- API contract tests for all paths, schemas, statuses, and additive compatibility.
- PostgreSQL concurrency tests prove one claim/ledger bundle under simultaneous
  requests and exact replay for the same idempotency key.
- AdMob verifier tests use official-format signed fixtures; invalid key,
  signature, altered query, wrong ad unit, expired session, and duplicate
  transaction cases must fail safely.
- Unity EditMode tests cover account isolation, server-time rendering, disabled
  provider state, claim replay, receipt correlation, and SSV session correlation.
- Production order: V11 migration, API deploy, AdMob callback configuration and
  test tool verification, Play product mapping, then Unity release.

## Migration and rollback risk

- Forward migration is additive and does not rewrite existing economy/store
  tables; only a short catalog lock is expected while adding the store-product
  fulfillment column.
- Roll back only before any limited-benefit claim or SSV callback is accepted.
  After grants begin, retain audit tables and disable the campaign instead of
  dropping data.
- If Play product IDs, AdMob ad unit, or SSV callback configuration are absent,
  keep affected offers `PROVIDER_UNAVAILABLE`; never enable a local grant path.
