package com.banking.tests.stubs;

/**
 * Centralises all WireMock response body JSON strings used across test suites.
 *
 * <p>Keeping payloads here (rather than inlined in test code) means:</p>
 * <ul>
 *   <li>Multiple suites can share the same CBS response format</li>
 *   <li>Updating a CBS response schema only requires editing one place</li>
 *   <li>Payloads are easy to swap for environment-specific variants</li>
 * </ul>
 */
public final class StubPayloads {

    private StubPayloads() {}

    // -------------------------------------------------------------------------
    // Funds Transfer
    // -------------------------------------------------------------------------

    public static String fundsTransferSuccess(String txnRef) {
        return """
                {
                  "txnReferenceNumber": "%s",
                  "status": "SUCCESS",
                  "responseCode": "00",
                  "description": "Transaction completed successfully",
                  "processingDate": "2024-03-15",
                  "channel": "INTERNET_BANKING",
                  "debitAccountNumber": "1234567890",
                  "creditAccountNumber": "0987654321",
                  "amount": 10000.00,
                  "currency": "INR"
                }
                """.formatted(txnRef);
    }

    public static String fundsTransferInsufficientFunds() {
        return """
                {
                  "txnReferenceNumber": null,
                  "status": "FAILED",
                  "responseCode": "51",
                  "description": "Insufficient funds in debit account",
                  "processingDate": "2024-03-15"
                }
                """;
    }

    public static String fundsTransferDuplicateRequest(String originalTxnRef) {
        return """
                {
                  "txnReferenceNumber": "%s",
                  "status": "DUPLICATE",
                  "responseCode": "94",
                  "description": "Duplicate transaction detected",
                  "processingDate": "2024-03-15"
                }
                """.formatted(originalTxnRef);
    }

    public static String fundsTransferCbsTimeout() {
        return """
                {
                  "status": "TIMEOUT",
                  "responseCode": "68",
                  "description": "CBS did not respond within the allowed time"
                }
                """;
    }

    // -------------------------------------------------------------------------
    // Account Inquiry
    // -------------------------------------------------------------------------

    public static String accountInquiryActive(String accountNumber) {
        return """
                {
                  "accountNumber": "%s",
                  "accountName": "Test Customer",
                  "accountType": "SAVINGS",
                  "currency": "INR",
                  "availableBalance": 50000.00,
                  "currentBalance": 55000.00,
                  "status": "ACTIVE",
                  "branchCode": "0001",
                  "responseCode": "00"
                }
                """.formatted(accountNumber);
    }

    public static String accountInquiryNotFound(String accountNumber) {
        return """
                {
                  "accountNumber": "%s",
                  "status": "NOT_FOUND",
                  "responseCode": "14",
                  "description": "Account number not found"
                }
                """.formatted(accountNumber);
    }

    public static String accountInquiryDormant(String accountNumber) {
        return """
                {
                  "accountNumber": "%s",
                  "accountName": "Dormant Customer",
                  "accountType": "SAVINGS",
                  "currency": "INR",
                  "availableBalance": 0.00,
                  "status": "DORMANT",
                  "responseCode": "62",
                  "description": "Account is dormant"
                }
                """.formatted(accountNumber);
    }

    // -------------------------------------------------------------------------
    // Balance Check
    // -------------------------------------------------------------------------

    public static String balanceCheckSuccess(String accountNumber, double balance) {
        return """
                {
                  "accountNumber": "%s",
                  "availableBalance": %.2f,
                  "currency": "INR",
                  "responseCode": "00",
                  "asOfDateTime": "2024-03-15T10:30:00"
                }
                """.formatted(accountNumber, balance);
    }

    // -------------------------------------------------------------------------
    // Mini Statement
    // -------------------------------------------------------------------------

    public static String miniStatementSuccess(String accountNumber) {
        return """
                {
                  "accountNumber": "%s",
                  "responseCode": "00",
                  "transactions": [
                    { "date": "2024-03-14", "description": "UPI Credit",      "amount": 5000.00,  "type": "CR", "balance": 50000.00 },
                    { "date": "2024-03-13", "description": "ATM Withdrawal",  "amount": 2000.00,  "type": "DR", "balance": 45000.00 },
                    { "date": "2024-03-12", "description": "NEFT Transfer",   "amount": 10000.00, "type": "DR", "balance": 47000.00 },
                    { "date": "2024-03-11", "description": "Salary Credit",   "amount": 75000.00, "type": "CR", "balance": 57000.00 },
                    { "date": "2024-03-10", "description": "Bill Payment",    "amount": 1500.00,  "type": "DR", "balance": 58500.00 }
                  ]
                }
                """.formatted(accountNumber);
    }

    // -------------------------------------------------------------------------
    // CBS system-level errors
    // -------------------------------------------------------------------------

    public static String cbsSystemError() {
        return """
                {
                  "responseCode": "96",
                  "description": "CBS system error — please retry"
                }
                """;
    }

    public static String cbsUnavailable() {
        return """
                {
                  "responseCode": "91",
                  "description": "CBS temporarily unavailable"
                }
                """;
    }
}
