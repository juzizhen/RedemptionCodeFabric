package com.juzizhen.rcode.sql.dao;

import com.juzizhen.rcode.sql.model.RedeemedHistory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.UUID;

public interface RedeemedHistoryDao {

    @SqlUpdate("INSERT INTO redeemed_history (code_id, player_uuid, player_name) " +
               "VALUES (:codeId, :playerUuid, :playerName)")
    @GetGeneratedKeys
    int insert(@BindBean RedeemedHistory history);

    @SqlQuery("SELECT id, code_id, player_uuid, player_name, redeemed_at " +
              "FROM redeemed_history " +
              "WHERE player_uuid = :playerUuid")
    @RegisterConstructorMapper(RedeemedHistory.class)
    List<RedeemedHistory> findByPlayerUuid(@Bind("playerUuid") UUID playerUuid);

    @SqlQuery("SELECT id, code_id, player_uuid, player_name, redeemed_at " +
              "FROM redeemed_history " +
              "WHERE code_id = :codeId")
    @RegisterConstructorMapper(RedeemedHistory.class)
    List<RedeemedHistory> findByCodeId(@Bind("codeId") int codeId);
}
