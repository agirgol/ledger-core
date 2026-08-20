package io.github.agirgol.ledger.api;

import java.time.Instant;

/**
 * A balance, with the moment it was taken at.
 *
 * <p>{@code asOf} is echoed back rather than left implicit. A balance without
 * the instant it was computed for is a number whose meaning changes the next
 * time a late correction is posted.
 */
public record BalanceView(String account, Instant asOf, MoneyView balance) {
}
