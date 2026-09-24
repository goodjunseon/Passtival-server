package com.passtival.backend.domain.authenticationkey.repository;

import java.sql.Types;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.passtival.backend.domain.authenticationkey.model.AuthenticationKey;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class AuthenticationKeyJdbcRepository {

  private static final int BATCH_SIZE = 1_000;
  private static final String INSERT_SQL =
      "INSERT INTO authentication_key (authentication_key, level) VALUES (?, ?)";

  private final JdbcTemplate jdbcTemplate;

  /**
   * 호출한 서비스의 트랜잭션에서 배치로 저장합니다. 생성된 ID는 입력 객체에 반영하지 않습니다.
   */
  public void insertAll(List<AuthenticationKey> entities) {
    if (entities.isEmpty()) {
      return;
    }

    jdbcTemplate.batchUpdate(INSERT_SQL, entities, BATCH_SIZE, (statement, entity) -> {
      statement.setString(1, entity.getAuthenticationKey());
      if (entity.getLevel() == null) {
        statement.setNull(2, Types.INTEGER);
      } else {
        statement.setInt(2, entity.getLevel());
      }
    });
  }
}
