/*
 * Copyright © 2017-2020 factcast.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.factcast.store.pgsql.internal;

import com.google.common.collect.Lists;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.factcast.core.Fact;
import org.factcast.core.snap.Snapshot;
import org.factcast.core.snap.SnapshotId;
import org.factcast.core.spec.FactSpec;
import org.factcast.core.store.AbstractFactStore;
import org.factcast.core.store.State;
import org.factcast.core.store.StateToken;
import org.factcast.core.store.TokenStore;
import org.factcast.core.subscription.FactTransformerService;
import org.factcast.core.subscription.Subscription;
import org.factcast.core.subscription.SubscriptionRequestTO;
import org.factcast.core.subscription.TransformationException;
import org.factcast.core.subscription.observer.FactObserver;
import org.factcast.store.pgsql.internal.StoreMetrics.OP;
import org.factcast.store.pgsql.internal.lock.FactTableWriteLock;
import org.factcast.store.pgsql.internal.query.PgQueryBuilder;
import org.factcast.store.pgsql.internal.snapcache.PgSnapshotCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A PostgreSQL based FactStore implementation
 *
 * @author uwe.schaefer@prisma-capacity.eu
 */
@Slf4j
public class PgFactStore extends AbstractFactStore {

  // is that interesting to configure?
  private static final int BATCH_SIZE = 500;

  @NonNull private final JdbcTemplate jdbcTemplate;

  @NonNull private final PgSubscriptionFactory subscriptionFactory;

  @NonNull private final FactTableWriteLock lock;

  @NonNull private final FactTransformerService factTransformerService;

  @NonNull private final PgMetrics metrics;

  @NonNull private final PgSnapshotCache snapCache;

  @Autowired
  public PgFactStore(
      @NonNull JdbcTemplate jdbcTemplate,
      @NonNull PgSubscriptionFactory subscriptionFactory,
      @NonNull TokenStore tokenStore,
      @NonNull FactTableWriteLock lock,
      @NonNull FactTransformerService factTransformerService,
      @NonNull PgSnapshotCache snapCache,
      @NonNull PgMetrics metrics) {
    super(tokenStore);

    this.jdbcTemplate = jdbcTemplate;
    this.subscriptionFactory = subscriptionFactory;
    this.lock = lock;
    this.snapCache = snapCache;
    this.metrics = metrics;
    this.factTransformerService = factTransformerService;
  }

  @Override
  public @NonNull Optional<Fact> fetchById(@NonNull UUID id) {
    return metrics.time(
        OP.FETCH_BY_ID,
        () ->
            jdbcTemplate
                .query(
                    PgConstants.SELECT_BY_ID,
                    new Object[] {"{\"id\":\"" + id + "\"}"},
                    this::extractFactFromResultSet)
                .stream()
                .findFirst());
  }

  @Override
  public @NonNull Optional<Fact> fetchByIdAndVersion(@NonNull UUID id, int version)
      throws TransformationException {

    val fact = fetchById(id);
    // map does not work here due to checked exception
    if (fact.isPresent()) {
      return Optional.of(factTransformerService.transformIfNecessary(fact.get(), version));
    } else {
      return fact;
    }
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void publish(@NonNull List<? extends Fact> factsToPublish) {
    metrics.time(
        OP.PUBLISH,
        () -> {
          try {
            lock.aquireExclusiveTXLock();

            List<Fact> copiedListOfFacts = Lists.newArrayList(factsToPublish);
            int numberOfFactsToPublish = factsToPublish.size();
            log.trace(
                "Inserting {} fact(s){}",
                numberOfFactsToPublish,
                numberOfFactsToPublish > BATCH_SIZE ? " in batches of " + BATCH_SIZE : "");
            jdbcTemplate.batchUpdate(
                PgConstants.INSERT_FACT,
                copiedListOfFacts,
                BATCH_SIZE,
                (statement, fact) -> {
                  statement.setString(1, fact.jsonHeader());
                  statement.setString(2, fact.jsonPayload());
                });
            // add serials to headers
            jdbcTemplate.batchUpdate(
                PgConstants.UPDATE_FACT_SERIALS,
                copiedListOfFacts,
                BATCH_SIZE,
                (statement, fact) -> {
                  String idMatch = "{\"id\":\"" + fact.id() + "\"}";
                  statement.setString(1, idMatch);
                });

          } catch (DuplicateKeyException dupkey) {
            throw new IllegalArgumentException(dupkey.getMessage());
          }
        });
  }

  private Fact extractFactFromResultSet(ResultSet resultSet, @SuppressWarnings("unused") int rowNum)
      throws SQLException {
    return PgFact.from(resultSet);
  }

  @NonNull
  private String extractStringFromResultSet(
      ResultSet resultSet, @SuppressWarnings("unused") int rowNum) throws SQLException {
    return resultSet.getString(1);
  }

  @Override
  public @NonNull Subscription subscribe(
      @NonNull SubscriptionRequestTO request, @NonNull FactObserver observer) {
    OP operation = request.continuous() ? OP.SUBSCRIBE_FOLLOW : OP.SUBSCRIBE_CATCHUP;
    return metrics.time(operation, () -> subscriptionFactory.subscribe(request, observer));
  }

  @Override
  public @NonNull OptionalLong serialOf(@NonNull UUID l) {
    return metrics.time(
        OP.SERIAL_OF,
        () -> {
          try {
            Long res =
                jdbcTemplate.queryForObject(
                    PgConstants.SELECT_SER_BY_ID,
                    new Object[] {"{\"id\":\"" + l + "\"}"},
                    Long.class);

            if (res != null && res > 0) {
              return OptionalLong.of(res);
            }

          } catch (EmptyResultDataAccessException ignore) {
            // ignore
          }
          return OptionalLong.empty();
        });
  }

  @Override
  public @NonNull Set<String> enumerateNamespaces() {
    return metrics.time(
        OP.ENUMERATE_NAMESPACES,
        () ->
            new HashSet<>(
                jdbcTemplate.query(
                    PgConstants.SELECT_DISTINCT_NAMESPACE, this::extractStringFromResultSet)));
  }

  @Override
  public @NonNull Set<String> enumerateTypes(@NonNull String ns) {
    return metrics.time(
        OP.ENUMERATE_TYPES,
        () ->
            new HashSet<>(
                jdbcTemplate.query(
                    PgConstants.SELECT_DISTINCT_TYPE_IN_NAMESPACE,
                    new Object[] {ns},
                    this::extractStringFromResultSet)));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public boolean publishIfUnchanged(
      @NonNull List<? extends Fact> factsToPublish, @NonNull Optional<StateToken> optionalToken) {
    return metrics.time(
        OP.PUBLISH_IF_UNCHANGED,
        () -> {
          lock.aquireExclusiveTXLock();
          return super.publishIfUnchanged(factsToPublish, optionalToken);
        });
  }

  @Override
  protected State getStateFor(@NonNull List<FactSpec> specs) {
    return metrics.time(
        OP.GET_STATE_FOR,
        () -> {
          PgQueryBuilder pgQueryBuilder = new PgQueryBuilder(specs);
          String stateSQL = pgQueryBuilder.createStateSQL();
          PreparedStatementSetter statementSetter =
              pgQueryBuilder.createStatementSetter(new AtomicLong(0));

          try {
            ResultSetExtractor<Long> rch =
                new ResultSetExtractor<Long>() {
                  @Override
                  public Long extractData(ResultSet resultSet)
                      throws SQLException, DataAccessException {
                    if (!resultSet.next()) {
                      return 0L;
                    } else {
                      return resultSet.getLong(1);
                    }
                  }
                };
            long lastSerial = jdbcTemplate.query(stateSQL, statementSetter, rch);
            return State.of(specs, lastSerial);
          } catch (EmptyResultDataAccessException lastSerialIs0Then) {
            return State.of(specs, 0);
          }
        });
  }

  @Override
  public long currentTime() {
    return jdbcTemplate.queryForObject(PgConstants.CURRENT_TIME_MILLIS, Long.class);
  }

  @Override
  public @NonNull Optional<Snapshot> getSnapshot(@NonNull SnapshotId id) {
    return metrics.time(OP.GET_SNAPSHOT, () -> snapCache.getSnapshot(id));
  }

  @Override
  public void setSnapshot(@NonNull Snapshot snapshot) {
    metrics.time(OP.SET_SNAPSHOT, () -> snapCache.setSnapshot(snapshot));
  }

  @Override
  public void clearSnapshot(@NonNull SnapshotId id) {
    metrics.time(OP.CLEAR_SNAPSHOT, () -> snapCache.clearSnapshot(id));
  }
}
