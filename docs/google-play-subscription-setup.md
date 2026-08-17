# Google Play monthly subscription setup

This runbook configures the two independent NYAON monthly subscriptions without
putting a real product ID, price, reward amount, purchase token, or credential in
Git. Values written as `<OPERATOR_INPUT_...>` must be supplied during release.

## 1. Apply the database migration

Apply `nayon_cloud` V13 before deploying the API. V13 creates the stable plans
`MONTHLY_GROWTH` and `MONTHLY_ADVANCED`, mapped to the `PREMIUM` and `ROYAL`
level-reward tracks. It intentionally leaves products and rewards inactive.

Do not run U13 after live subscription or claim data exists; it drops audit and
entitlement tables.

## 2. Create Play Console products

For Android package `com.korion.Nayon`, create two subscription products:

- `<OPERATOR_INPUT_GROWTH_PRODUCT_ID>` for `MONTHLY_GROWTH`
- `<OPERATOR_INPUT_ADVANCED_PRODUCT_ID>` for `MONTHLY_ADVANCED`

Give each product one active base plan with a one-month, auto-renewing billing
period. Configure price and regional availability only in Play Console. The two
products are independent: owning one never grants the other.

## 3. Configure Android Publisher access

Create or select a Google Cloud service account with the minimum Play Console
permissions needed to read subscriptions. Store its JSON outside Git and set:

```dotenv
GOOGLE_PLAY_CREDENTIALS_HOST_FILE=<OPERATOR_INPUT_SECURE_JSON_PATH>
GOOGLE_PLAY_PACKAGE_NAME=com.korion.Nayon
```

The deploy mounts that file read-only at
`/run/secrets/google-play-service-account.json`.

## 4. Configure authenticated RTDN

Create a Pub/Sub topic for Real-time developer notifications and grant Google
Play permission to publish to it. Create a push subscription targeting:

```text
https://<OPERATOR_INPUT_API_HOST>/api/v1/public/google-play/rtdn
```

Enable authenticated push with a dedicated service account. Grant the Pub/Sub
service agent permission to mint an OIDC token for that account. Set the exact
audience configured on the push subscription:

```dotenv
GOOGLE_PLAY_RTDN_JWK_SET_URI=https://www.googleapis.com/oauth2/v3/certs
GOOGLE_PLAY_RTDN_AUDIENCE=https://<OPERATOR_INPUT_API_HOST>/api/v1/public/google-play/rtdn
GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL=<OPERATOR_INPUT_PUSH_SERVICE_ACCOUNT_EMAIL>
```

The endpoint validates issuer, audience, service-account email, package name,
message ID, and notification payload. Pub/Sub retries are safe because message
IDs are persisted idempotently.

## 5. Insert versioned catalog data

After V13 and API health pass, insert one `store_products` row and one immutable
`store_product_versions` row for each plan's existing `store_offers` row. Use
new UUIDs and operator-supplied product IDs. Subscription product versions must
use `fulfillment_type='SUBSCRIPTION'` with all direct reward columns null.

```sql
begin;

insert into store_products(
    id, offer_id, platform, store_product_id, product_type, active)
select '<OPERATOR_INPUT_GROWTH_PRODUCT_UUID>', id, 'GOOGLE_PLAY',
       '<OPERATOR_INPUT_GROWTH_PRODUCT_ID>', 'SUBSCRIPTION', false
from store_offers where offer_code = 'monthly_growth';

insert into store_product_versions(
    id, product_id, version, reward_asset_type, reward_asset_code,
    reward_amount, valid_from, active, fulfillment_type)
values (
    '<OPERATOR_INPUT_GROWTH_VERSION_UUID>',
    '<OPERATOR_INPUT_GROWTH_PRODUCT_UUID>', 1,
    null, null, null, now(), false, 'SUBSCRIPTION');

-- Repeat the two inserts for monthly_advanced with independent UUIDs and
-- <OPERATOR_INPUT_ADVANCED_PRODUCT_ID>.

commit;
```

Insert versioned rows in `subscription_benefit_versions` and
`level_reward_versions` using approved product values. Never update the meaning
of an active version; close its validity window and insert a new version instead.
Activate products, product versions, benefits, and reward rows only after a
license-test verification succeeds.

## 6. License-test scenarios

Use Play Console license testers and a Play-installed internal-test build. Do not
perform a real charge during deployment verification.

- purchase and acknowledge each plan independently;
- verify initial reward is granted once per account and plan;
- verify daily reward is claimable once per account, plan, and server day;
- cancel while entitlement remains valid through expiry;
- exercise grace period, account hold, expiry, revoke, and resubscribe;
- deliver the same RTDN message twice and confirm one stored result;
- confirm a wrong audience, service account, package, product, or account hash is rejected;
- confirm lifetime level rewards cannot be claimed twice and never reset on renewal.

After activation, monitor health, RTDN failure codes, verification latency,
duplicate-event counts, and logs for accidental token or credential exposure.
