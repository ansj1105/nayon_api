package com.nayon.api.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcStoreCatalogRepository implements StoreCatalogRepository {

    private final JdbcTemplate jdbc;

    public JdbcStoreCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StoreCatalogOffer> findActiveOffers(StorePlatform platform) {
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
                   and v.valid_from <= now()
                   and (v.valid_until is null or v.valid_until > now())
                 order by o.display_order, o.offer_code
                """, (rs, rowNumber) -> new StoreCatalogOffer(
                rs.getString("offer_code"),
                rs.getString("store_product_id"),
                rs.getString("product_type"),
                rs.getString("reward_asset_code"),
                rs.getLong("reward_amount"),
                rs.getInt("version")), platform.name());
    }
}
