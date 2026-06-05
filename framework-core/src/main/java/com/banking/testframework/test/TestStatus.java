package com.banking.testframework.test;

/**
 * Represents the execution status of a single test case.
 */
public enum TestStatus {

    /** Test executed and all assertions passed. */
    PASSED,

    /** Test executed but at least one assertion failed. */
    FAILED,

    /** Test was not executed (e.g. dependency failed, explicit skip). */
    SKIPPED,

    /** Test threw an unexpected exception during execution. */
    ERROR
}
