package com.juzizhen.rcode.sql.dao;

import com.juzizhen.rcode.sql.model.RedemptionCode;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;

public interface RedemptionCodeDao {

    @SqlUpdate("INSERT INTO redemption_codes (code, item_json, uses_left, max_uses, expiration_date) " +
               "VALUES (:code, :itemJson, :usesLeft, :maxUses, :expirationDate)")
    @GetGeneratedKeys
    int insert(@BindBean RedemptionCode code);

    @SqlQuery("SELECT id, code, item_json, uses_left, max_uses, expiration_date, created_at " +
              "FROM redemption_codes " +
              "WHERE code = :code")
    @RegisterConstructorMapper(RedemptionCode.class)
    Optional<RedemptionCode> findByCode(@Bind("code") String code);

    @SqlUpdate("UPDATE redemption_codes SET uses_left = :usesLeft WHERE id = :id")
    int updateUsesLeft(@Bind("id") int id, @Bind("usesLeft") int usesLeft);

    @SqlUpdate("DELETE FROM redemption_codes WHERE id = :id")
    int deleteById(@Bind("id") int id);
}
