package com.sugr.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Os#psQuote} feeds user-controlled strings (menu labels, tooltips, file paths,
 * accelerators) into PowerShell command lines - getting the escaping wrong is a script
 * injection, so pin the behaviour down.
 */
final class OsTest {

    @Test
    void wrapsAPlainStringInSingleQuotes() {
        assertEquals("'hello'", Os.psQuote("hello"));
    }

    @Test
    void doublesEmbeddedSingleQuotes() {
        assertEquals("'it''s'", Os.psQuote("it's"));
        assertEquals("''''", Os.psQuote("'"));
    }

    @Test
    void leavesDoubleQuotesAndDollarSignsInert() {
        // Inside a single-quoted PowerShell literal these are all just characters.
        assertEquals("'$(Get-Process) \"x\" `n'", Os.psQuote("$(Get-Process) \"x\" `n"));
    }

    @Test
    void handlesAnEmptyString() {
        assertEquals("''", Os.psQuote(""));
    }

    @Test
    void handlesAWindowsPathWithSpaces() {
        assertEquals("'C:\\Program Files\\My App\\icon.ico'",
                Os.psQuote("C:\\Program Files\\My App\\icon.ico"));
    }

    @Test
    void aQuoteBreakoutAttemptStaysQuoted() {
        // "'; Remove-Item C:\ -Recurse #" must not escape the literal.
        assertEquals("'''; Remove-Item C:\\ -Recurse #'",
                Os.psQuote("'; Remove-Item C:\\ -Recurse #"));
    }
}
