package site.asm0dey.slidev.polls.persistence;

import static site.asm0dey.slidev.polls.persistence.jooq.Tables.ADMIN_USER;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import site.asm0dey.slidev.polls.core.domain.AdminUser;
import site.asm0dey.slidev.polls.core.service.AdminUserRepository;

@Repository
public class AdminUserRepositoryImpl implements AdminUserRepository {

  private final DSLContext dsl;

  public AdminUserRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public long count() {
    return dsl.fetchCount(ADMIN_USER);
  }

  @Override
  public boolean existsByUsername(String username) {
    return dsl.fetchExists(
        dsl.selectOne().from(ADMIN_USER).where(ADMIN_USER.USERNAME.eq(username)));
  }

  @Override
  public void insert(String username, String passwordHash) {
    dsl.insertInto(ADMIN_USER)
        .set(ADMIN_USER.USERNAME, username)
        .set(ADMIN_USER.PASSWORD_HASH, passwordHash)
        .execute();
  }

  @Override
  public List<AdminUser> listAll() {
    return dsl.select(ADMIN_USER.USERNAME, ADMIN_USER.CREATED_AT)
        .from(ADMIN_USER)
        .orderBy(ADMIN_USER.CREATED_AT.asc())
        .fetch(
            r ->
                new AdminUser(
                    r.get(ADMIN_USER.USERNAME),
                    r.get(ADMIN_USER.CREATED_AT, OffsetDateTime.class).toInstant()));
  }

  @Override
  public Optional<String> findPasswordHash(String username) {
    return dsl.select(ADMIN_USER.PASSWORD_HASH)
        .from(ADMIN_USER)
        .where(ADMIN_USER.USERNAME.eq(username))
        .fetchOptional()
        .map(r -> r.get(ADMIN_USER.PASSWORD_HASH));
  }

  @Override
  public Optional<String> findBootstrapAdminUsername() {
    return dsl.select(ADMIN_USER.USERNAME)
        .from(ADMIN_USER)
        .orderBy(ADMIN_USER.CREATED_AT.asc(), ADMIN_USER.USERNAME.asc())
        .limit(1)
        .fetchOptional(ADMIN_USER.USERNAME);
  }

  @Override
  public void updatePasswordHash(String username, String passwordHash) {
    dsl.update(ADMIN_USER)
        .set(ADMIN_USER.PASSWORD_HASH, passwordHash)
        .where(ADMIN_USER.USERNAME.eq(username))
        .execute();
  }
}
