# Weekly Gift Verification Checklist

Run this checklist whenever weekly check-in, reward periods, economy credit, or
the V14 weekly-gift schema changes.

- [ ] Apply and roll back V14 against real PostgreSQL:
  `bash scripts/verify-v14.sh` in `nayon_cloud`.
- [ ] Keep production reward configuration empty until the reward policy is
  approved. Add `DIAMOND 1` only inside the isolated test database.
- [ ] Create one test account with bootstrapped economy state.
- [ ] Check in on three distinct KST dates in the same Monday-based week.
- [ ] Confirm the first two check-ins are not claimable.
- [ ] Confirm the third check-in reports `loginDays=3` and `claimable=true`.
- [ ] Claim once with an idempotency key and confirm `DIAMOND=1` in the returned
  economy snapshot.
- [ ] Query `economy_ledger` and confirm exactly one `WEEKLY_GIFT` credit row.
- [ ] Replay the same idempotency key and confirm the same response with no
  second ledger credit.
- [ ] Retry with a different idempotency key and confirm
  `WEEKLY_GIFT_ALREADY_CLAIMED`.
- [ ] Run the executable scenario:
  `env NAYON_CLOUD_DIR=/home/ubuntu/work/.worktrees/nayon-cloud-develop-sj NAYON_TEST_PATTERN=WeeklyGiftPostgresTest bash scripts/verify-postgres-integration.sh`.
