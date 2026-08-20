package io.github.agirgol.ledger.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The HTTP surface, against a real database.
 *
 * <p>Most of these assert refusals rather than successes. Posting a balanced
 * transaction is the easy half; what an API over a ledger is judged on is
 * whether a caller who got it wrong is told what to change, and whether a
 * retried request charges the amount once.
 *
 * <p>Accounts are opened under a fresh id per test. The tables reject DELETE,
 * so there is no tearing down between cases — which is the schema behaving as
 * designed, and the tests work with it rather than around it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LedgerApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private MockMvc http;

    private String cash;
    private String revenue;

    @BeforeEach
    void openAccounts() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        cash = "cash-" + suffix;
        revenue = "revenue-" + suffix;
        open(cash, "Cash", "ASSET", "TRY");
        open(revenue, "Revenue", "REVENUE", "TRY");
    }

    @Test
    @DisplayName("opening the same account twice is not an error")
    void openingIsIdempotent() throws Exception {
        open(cash, "Cash", "ASSET", "TRY");

        http.perform(get("/accounts/{id}", cash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cash))
                .andExpect(jsonPath("$.type").value("ASSET"))
                .andExpect(jsonPath("$.currency").value("TRY"));
    }

    @Test
    @DisplayName("a balanced transaction is created and readable at its Location")
    void postsAndReadsBack() throws Exception {
        String body = http.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sale("100.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/transactions/")))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        http.perform(get("/transactions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    @DisplayName("an unbalanced transaction is refused with the residue named")
    void refusesUnbalanced() throws Exception {
        http.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "occurredAt": "2026-01-15T10:00:00Z",
                                  "description": "Sale",
                                  "entries": [
                                    {"account": "%s", "side": "DEBIT",  "amount": "100.00", "currency": "TRY"},
                                    {"account": "%s", "side": "CREDIT", "amount": "60.00",  "currency": "TRY"}
                                  ]
                                }
                                """.formatted(cash, revenue)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("/problems/unbalanced-transaction"))
                // Debits exceed credits by exactly the missing 40.00, which is
                // the number the caller has to act on.
                .andExpect(jsonPath("$.imbalance.TRY").value("40.00"));
    }

    @Test
    @DisplayName("the same idempotency key posts once, and says so on the retry")
    void retryUnderTheSameKeyPostsOnce() throws Exception {
        String key = "key-" + UUID.randomUUID();

        String first = http.perform(post("/transactions")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sale("100.00")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = first.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        http.perform(post("/transactions")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sale("100.00")))
                // 200, not 201: this call created nothing.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        // The point of the header is the money, not the status code.
        http.perform(get("/accounts/{id}/balance", cash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.amount").value("100.00"));
    }

    @Test
    @DisplayName("posting against an account nobody opened names that account")
    void refusesUnknownAccount() throws Exception {
        http.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "occurredAt": "2026-01-15T10:00:00Z",
                                  "description": "Sale",
                                  "entries": [
                                    {"account": "nobody-opened-this", "side": "DEBIT",  "amount": "100.00", "currency": "TRY"},
                                    {"account": "%s", "side": "CREDIT", "amount": "100.00", "currency": "TRY"}
                                  ]
                                }
                                """.formatted(revenue)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.accounts[0]").value("nobody-opened-this"));
    }

    @Test
    @DisplayName("an amount finer than its currency is refused, not rounded")
    void refusesTooManyDecimals() throws Exception {
        http.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sale("100.001")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("decimal places")));
    }

    @Test
    @DisplayName("a currency that is not ISO 4217 is refused by name")
    void refusesUnknownCurrency() throws Exception {
        http.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "x", "name": "X", "type": "ASSET", "currency": "XYZ"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("ISO 4217")));
    }

    @Test
    @DisplayName("a point-in-time balance excludes what was posted after it")
    void balanceAsOfExcludesLater() throws Exception {
        http.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sale("100.00")))
                .andExpect(status().isCreated());

        http.perform(get("/accounts/{id}/balance", cash).param("asOf", "2026-01-15T09:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.amount").value("0.00"))
                .andExpect(jsonPath("$.asOf").value("2026-01-15T09:00:00Z"));

        http.perform(get("/accounts/{id}/balance", cash).param("asOf", "2026-01-15T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.amount").value("100.00"));
    }

    @Test
    @DisplayName("a balance for an account nobody opened is a 404, not a zero")
    void unknownAccountBalanceIsNotZero() throws Exception {
        http.perform(get("/accounts/{id}/balance", "never-opened"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/no-such-account"));
    }

    private void open(String id, String name, String type, String currency) throws Exception {
        http.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "%s", "name": "%s", "type": "%s", "currency": "%s"}
                                """.formatted(id, name, type, currency)))
                .andExpect(status().isOk());
    }

    private String sale(String amount) {
        return """
                {
                  "occurredAt": "2026-01-15T10:00:00Z",
                  "description": "Sale",
                  "entries": [
                    {"account": "%s", "side": "DEBIT",  "amount": "%s", "currency": "TRY"},
                    {"account": "%s", "side": "CREDIT", "amount": "%s", "currency": "TRY"}
                  ]
                }
                """.formatted(cash, amount, revenue, amount);
    }
}
