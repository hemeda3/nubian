package com.nubian.ai.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlameLedgerTest {

    @Test
    void invalidatedRouteFromVerifierRejectIsBlockedOnSameCheckpoint() {
        BlameLedger ledger = new BlameLedger();
        String hash = BlameLedger.routeHash("click", "Save as... Ctrl+S", 352, 205, null, null);

        int row = ledger.recordPending("1.1", 8, "Save as... Ctrl+S", hash);
        ledger.resolvePendingFor("1.1", BlameLedger.Status.VERIFIER_REJECT, "wrong dialog");

        assertTrue(ledger.isInvalidated("1.1", hash));
        assertEquals(1, ledger.invalidCount("1.1"));
        assertNotEquals(0, row);
    }

    @Test
    void coordinateDriftWithinThirtyPxBucketHashesIdentically() {
        String a = BlameLedger.routeHash("click", "Save as... Ctrl+S", 352, 205, null, null);
        String b = BlameLedger.routeHash("click", "Save as... menu item", 358, 205, null, null);
        assertEquals(a, b, "drift inside one 30px bucket and similar text must collapse to one route");
    }

    @Test
    void differentPixelBucketMakesDifferentRoute() {
        String a = BlameLedger.routeHash("click", "Save Image As...", 352, 205, null, null);
        String b = BlameLedger.routeHash("click", "Save Page As...", 352, 305, null, null);
        assertNotEquals(a, b);
    }

    @Test
    void nonPointerActionsHashOnSymbolicFields() {
        String a = BlameLedger.routeHash("hotkey", null, null, null, "ctrl+s", null);
        String b = BlameLedger.routeHash("hotkey", null, null, null, "ctrl+o", null);
        assertNotEquals(a, b);
        assertEquals(BlameLedger.routeHash("type_text", null, null, null, null, "hello"),
                BlameLedger.routeHash("type_text", null, null, null, null, "hello"));
    }

    @Test
    void invalidatedRouteFromParentBlocksDescendantCheckpoint() {
        BlameLedger ledger = new BlameLedger();
        String hash = BlameLedger.routeHash("click", "Save as", 352, 205, null, null);
        ledger.recordPending("1.1", 8, "Save as", hash);
        ledger.resolvePendingFor("1.1", BlameLedger.Status.VERIFIER_REJECT, "wrong dialog");

        assertTrue(ledger.isInvalidated("1.1.2", hash),
                "child checkpoint must inherit parent's invalidated routes");
    }

    @Test
    void siblingCheckpointDoesNotInheritInvalidation() {
        BlameLedger ledger = new BlameLedger();
        String hash = BlameLedger.routeHash("click", "Save as", 352, 205, null, null);
        ledger.recordPending("1.1", 8, "Save as", hash);
        ledger.resolvePendingFor("1.1", BlameLedger.Status.VERIFIER_REJECT, "wrong");

        assertFalse(ledger.isInvalidated("1.2", hash),
                "sibling does not inherit; only ancestors propagate");
    }

    @Test
    void noChangeMarksRowInvalidImmediately() {
        BlameLedger ledger = new BlameLedger();
        String hash = BlameLedger.routeHash("click", "Save", 837, 234, null, null);
        int rowId = ledger.recordPending("1.1", 9, "Save button", hash);
        ledger.update(rowId, BlameLedger.Status.NO_CHANGE, "screen_delta_pct=0.00");

        assertTrue(ledger.isInvalidated("1.1", hash));
    }

    @Test
    void verifierAcceptDoesNotInvalidate() {
        BlameLedger ledger = new BlameLedger();
        String hash = BlameLedger.routeHash("click", "OK", 100, 100, null, null);
        ledger.recordPending("2.1", 5, "OK", hash);
        ledger.resolvePendingFor("2.1", BlameLedger.Status.VERIFIER_ACCEPT, "looks right");

        assertFalse(ledger.isInvalidated("2.1", hash));
        assertEquals(0, ledger.invalidCount("2.1"));
    }

    @Test
    void summaryListsInvalidatedRoutesAsStructuredBlock() {
        BlameLedger ledger = new BlameLedger();
        String h1 = BlameLedger.routeHash("click", "Save as... Ctrl+S", 352, 205, null, null);
        String h2 = BlameLedger.routeHash("click", "Save button", 837, 234, null, null);
        int r1 = ledger.recordPending("1.1", 8, "Save as... Ctrl+S", h1);
        int r2 = ledger.recordPending("1.1", 9, "Save button", h2);
        ledger.update(r1, BlameLedger.Status.VERIFIER_REJECT, "wrong dialog");
        ledger.update(r2, BlameLedger.Status.NO_CHANGE, "screen_delta_pct=0.00");

        String summary = ledger.invalidatedSummary("1.1");
        assertTrue(summary.contains("[invalidated routes for this checkpoint]"));
        assertTrue(summary.contains("Save as... Ctrl+S"));
        assertTrue(summary.contains("Save button"));
        assertTrue(summary.contains("verifier_reject") || summary.contains("VERIFIER_REJECT".toLowerCase()));
        assertTrue(summary.contains("no_change") || summary.contains("NO_CHANGE".toLowerCase()));
    }

    @Test
    void clearWipesAllRows() {
        BlameLedger ledger = new BlameLedger();
        ledger.recordPending("1.1", 1, "x", BlameLedger.routeHash("click", "x", 0, 0, null, null));
        assertEquals(1, ledger.rowsFor("1.1").size());

        ledger.clear();
        assertEquals(0, ledger.rowsFor("1.1").size());
    }

    @Test
    void duplicateEmissionWithoutVerifierAutoInvalidatesPriorPendingOnThirdStrike() {
        BlameLedger ledger = new BlameLedger();
        String hash = BlameLedger.routeHash("right_click", null, 542, 599, null, null);
        int row1 = ledger.recordPending("1.1", 6, "image", hash);

        ledger.invalidatePriorPending("1.1", hash);
        assertFalse(ledger.isInvalidated("1.1", hash),
                "second emission still allowed (one prior PENDING, retry permitted)");

        int row2 = ledger.recordPending("1.1", 7, "image", hash);
        ledger.invalidatePriorPending("1.1", hash);

        assertTrue(ledger.isInvalidated("1.1", hash),
                "third emission of same route auto-marks the priors as invalid");
        assertNotEquals(0, row1);
        assertNotEquals(0, row2);
    }

    @Test
    void duplicateEmissionFromDescendantInvalidatesAncestorPendingAfterThirdStrike() {
        BlameLedger ledger = new BlameLedger();
        String hash = BlameLedger.routeHash("right_click", null, 542, 599, null, null);
        ledger.recordPending("1.1", 6, "image", hash);
        ledger.recordPending("1.1", 7, "image", hash);

        ledger.invalidatePriorPending("1.1.1", hash);

        assertTrue(ledger.isInvalidated("1.1.1", hash),
                "descendant re-emission with two prior PENDING ancestors flips both");
    }

    @Test
    void invalidatePriorPendingDoesNotTouchAcceptOrFailedRows() {
        BlameLedger ledger = new BlameLedger();
        String hash = BlameLedger.routeHash("click", "x", 100, 100, null, null);
        int rowAccept = ledger.recordPending("2.1", 1, "x", hash);
        ledger.update(rowAccept, BlameLedger.Status.VERIFIER_ACCEPT, "ok");

        ledger.invalidatePriorPending("2.1", hash);

        assertFalse(ledger.isInvalidated("2.1", hash),
                "an already-accepted row must not be downgraded by duplicate-emission rule");
    }

    @Test
    void toolInventoryReportsTriedAndInvalidCountsPerToolClass() {
        BlameLedger ledger = new BlameLedger();
        String h1 = BlameLedger.routeHash("click", "x", 100, 100, null, null);
        String h2 = BlameLedger.routeHash("click", "y", 200, 200, null, null);
        String h3 = BlameLedger.routeHash("hotkey", null, null, null, "ctrl+s", null);

        int r1 = ledger.recordPending("1.1", 1, "x", h1);
        int r2 = ledger.recordPending("1.1", 2, "y", h2);
        int r3 = ledger.recordPending("1.1", 3, "save", h3);
        ledger.update(r1, BlameLedger.Status.NO_CHANGE, "");
        ledger.update(r2, BlameLedger.Status.VERIFIER_REJECT, "");

        String inventory = ledger.toolInventory("1.1");
        assertTrue(inventory.contains("[tool inventory for this checkpoint chain]"));
        assertTrue(inventory.contains("click: tried=2, invalid=2"),
                "click tried twice, both invalid: " + inventory);
        assertTrue(inventory.contains("hotkey: tried=1, invalid=0"),
                "hotkey tried once, still pending so 0 invalid: " + inventory);
    }

    @Test
    void toolInventoryEmptyWhenNoRows() {
        BlameLedger ledger = new BlameLedger();
        assertEquals("", ledger.toolInventory("1.1"));
    }

    @Test
    void symbolicHashIgnoresPunctuationAndCase() {
        String a = BlameLedger.routeHash("list_apps", "GIMP", null, null, null, null);
        String b = BlameLedger.routeHash("list_apps", "  gimp  ", null, null, null, null);
        assertEquals(a, b);
    }
}
