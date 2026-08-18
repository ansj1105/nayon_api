package com.nayon.api.store;

import com.nayon.api.time.ServerClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcStoreCatalogRepository implements StoreCatalogRepository {

    private final JdbcTemplate jdbc;
    private final ServerClock clock;

    public JdbcStoreCatalogRepository(JdbcTemplate jdbc, ServerClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public List<StoreCatalogOffer> findActiveOffers(StorePlatform platform) {
        java.sql.Timestamp now = java.sql.Timestamp.from(clock.now());
        return jdbc.query("""
                select o.offer_code, p.store_product_id, p.product_type,
                       v.reward_asset_code, v.reward_amount, v.version
                  from store_offers o
                  join store_products p on p.offer_id = o.id
                  join store_product_versions v on v.product_id = p.id
                 where o.active
                   and p.active
                   and p.platform = ?
                   and v.active
                   and v.fulfillment_type = 'DIRECT_CURRENCY'
                   and v.valid_from <= ?
                   and (v.valid_until is null or v.valid_until > ?)
                 order by o.display_order, o.offer_code
                """, (rs, rowNumber) -> new StoreCatalogOffer(
                rs.getString("offer_code"),
                rs.getString("store_product_id"),
                rs.getString("product_type"),
                rs.getString("reward_asset_code"),
                rs.getLong("reward_amount"),
                rs.getInt("version")), platform.name(), now, now);
    }
}
