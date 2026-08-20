package io.github.agirgol.ledger.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TransactionRepository extends JpaRepository<TransactionEntity, String> {

    Optional<TransactionEntity> findByIdempotencyKey(String idempotencyKey);

    @Query("select t from TransactionEntity t order by t.occurredAt, t.id")
    List<TransactionEntity> findAllOrdered();

    /**
     * Transactions that had occurred by a given moment.
     *
     * <p>Filtered on {@code occurredAt} rather than {@code recordedAt}: a
     * correction posted in March for a January event belongs in January's
     * balance. Restating a closed period is the normal case, not an anomaly.
     */
    @Query("select t from TransactionEntity t where t.occurredAt <= :asOf order by t.occurredAt, t.id")
    List<TransactionEntity> findOccurredBy(@Param("asOf") Instant asOf);
}
