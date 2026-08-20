package io.github.agirgol.ledger.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EntryRepository extends JpaRepository<EntryEntity, Long> {

    /**
     * Debits minus credits for one account, as of a moment.
     *
     * <p>Aggregated in the database rather than by loading entries, because a
     * ledger's whole point is that it keeps growing: an account with a million
     * entries should not need a million objects to answer what its balance is.
     *
     * <p>The time bound reads {@code e.occurredAt} rather than joining back to
     * the transaction. The two always agree — the database derives one from the
     * other and neither table accepts an update — and the join was measured at
     * three times the cost of the sum it qualified.
     *
     * <p>The sign is raw — debits positive — and is turned into a balance by
     * the account's type in {@link LedgerStore}. Doing that here would mean the
     * query knowing about account classification, which is domain knowledge and
     * belongs where it can be tested without a database.
     */
    @Query("""
            select coalesce(sum(case when e.side = io.github.agirgol.ledger.domain.Side.DEBIT
                                     then e.amount else -e.amount end), 0)
            from EntryEntity e
            where e.accountId = :accountId
              and e.currency = :currency
              and e.occurredAt <= :asOf
            """)
    BigDecimal netByAccount(
            @Param("accountId") String accountId,
            @Param("currency") String currency,
            @Param("asOf") Instant asOf);
}
