# Google Play store setup

## Play Console

Create six **one-time products** for Android package `com.korion.Nayon`:

| Offer code | Google product ID | Current server reward |
|---|---|---:|
| `diamond_100` | `nayon.diamond.100` | 100 DIAMOND |
| `diamond_600` | `nayon.diamond.600` | 600 DIAMOND |
| `diamond_1500` | `nayon.diamond.1500` | 1,500 DIAMOND |
| `diamond_3000` | `nayon.diamond.3000` | 3,000 DIAMOND |
| `diamond_7000` | `nayon.diamond.7000` | 7,000 DIAMOND |
| `diamond_15000` | `nayon.diamond.15000` | 15,000 DIAMOND |

Prices are intentionally absent from the NYAON database and API. Set/change each
country price in Play Console; Unity must display the localized price returned by
Google Play Billing.

Grant the Google service account Android Publisher access to this app and store its
JSON in the runtime secret store. Materialize it on the API host as a mode-`0400`
file, set `GOOGLE_PLAY_CREDENTIALS_HOST_FILE`, and set a separate random
`STORE_ACCOUNT_HASH_KEY`. Never copy either value into git or logs.
Keep the account-hash key stable for the lifetime of purchases created with it;
rotating it without a dual-key migration makes restored pending purchases fail account binding.

## Database activation

Apply `nayon_cloud` V9 first. After the Play products exist, activate mappings and
reward version 1 in one transaction. Use operator-generated UUIDs in place of the
placeholders below; do not reuse IDs between rows.

```sql
begin;

-- Repeat once per offer using the table above.
insert into store_products(
    id, offer_id, platform, store_product_id, product_type, active)
select '<new-product-uuid>', id, 'GOOGLE_PLAY', 'nayon.diamond.100',
       'ONE_TIME', true
  from store_offers where offer_code = 'diamond_100';

insert into store_product_versions(
    id, product_id, version, reward_asset_type, reward_asset_code,
    reward_amount, valid_from, active)
values ('<new-version-uuid>', '<new-product-uuid>', 1,
        'CURRENCY', 'DIAMOND', 100, now(), true);

commit;
```

To change a reward, deactivate the old version and insert a new version number in
the same transaction. To change a Google product ID, deactivate the old
`store_products` row and insert a new product plus reward version. Existing receipts
retain their original product-version reference.

## Release order and checks

1. Apply V9 and verify the four tables plus six offer rows.
2. Configure the service-account file and account-hash secret.
3. Deploy the API; confirm health and that an empty catalog is safe.
4. Create/activate Play Console products and then activate matching DB rows.
5. Query the catalog with a real Cognito account and compare all six product IDs.
6. Run a Play license-tester purchase; verify one receipt, one `STORE_PURCHASE`
   ledger row, API state `GRANTED`, and Unity IAP purchase confirmation. Unity IAP
   owns Google consumption after the server grant; the API must not consume the
   same token.

Rollback trigger before product activation: API startup/health regression. Disable
the store product rows before any API rollback. U9 is destructive and must never run
after real receipts exist without an explicit data-retention/export decision.
