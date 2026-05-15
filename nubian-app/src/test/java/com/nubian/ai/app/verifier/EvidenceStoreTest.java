package com.nubian.ai.app.verifier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceStoreTest {

    private EvidenceStore store;

    @BeforeEach
    void setUp() {
        store = new EvidenceStore();
    }

    @Test
    void putShot_assignsMonotonicallyIncreasingCaptureSeq() {
        byte[] png = new byte[]{1, 2, 3};
        int seq1 = store.putShot(1, "cp1", png);
        int seq2 = store.putShot(1, "cp1", png);
        int seq3 = store.putShot(2, "cp2", png);
        assertTrue(seq2 > seq1);
        assertTrue(seq3 > seq2);
    }

    @Test
    void getShotsByTask_returnsOnlyShotsForThatTask() {
        byte[] a = new byte[]{10};
        byte[] b = new byte[]{20};
        store.putShot(1, "cp1", a);
        store.putShot(2, "cp2", b);
        store.putShot(1, "cp1", a);

        List<EvidenceStore.Shot> task1 = store.getShotsByTask(1, 0);
        List<EvidenceStore.Shot> task2 = store.getShotsByTask(2, 0);
        assertEquals(2, task1.size());
        assertEquals(1, task2.size());
        assertTrue(task1.stream().allMatch(s -> s.taskSeq() == 1));
    }

    @Test
    void getShotsByTask_last_limitsResults() {
        byte[] png = new byte[]{1};
        for (int i = 0; i < 5; i++) {
            store.putShot(1, "cp", png);
        }
        List<EvidenceStore.Shot> last2 = store.getShotsByTask(1, 2);
        assertEquals(2, last2.size());
        // should be the two most recent (highest captureSeq)
        assertTrue(last2.get(0).captureSeq() < last2.get(1).captureSeq());
    }

    @Test
    void getShotsInRange_filtersCorrectly() {
        byte[] png = new byte[]{1};
        store.putShot("run1", 1, "cp", 1000L, png);
        store.putShot("run1", 1, "cp", 2000L, png);
        store.putShot("run1", 1, "cp", 3000L, png);

        List<EvidenceStore.Shot> range = store.getShotsInRange(1500L, 3000L);
        assertEquals(2, range.size());
        assertEquals(2000L, range.get(0).capturedAtMs());
        assertEquals(3000L, range.get(1).capturedAtMs());
    }

    @Test
    void getActionPoints_returnsInInsertionOrder() {
        store.putActionPoint(1, 100, 200, "click");
        store.putActionPoint(1, 300, 400, "double_click");
        store.putActionPoint(2, 50, 50, "click");

        List<EvidenceStore.ActionPoint> pts = store.getActionPoints(1);
        assertEquals(2, pts.size());
        assertEquals(100, pts.get(0).x());
        assertEquals(300, pts.get(1).x());
        assertEquals(List.of(), store.getActionPoints(99));
    }

    @Test
    void clear_resetsAllState() {
        byte[] png = new byte[]{1};
        store.putShot(1, "cp", png);
        store.putActionPoint(1, 10, 20, "click");
        store.putHotkey(1, "ctrl+s");
        store.putTypeText(1, "hello");
        store.clear();

        assertEquals(0, store.shotCount());
        assertEquals(List.of(), store.getShotsByTask(1, 0));
        assertEquals(List.of(), store.getActionPoints(1));
        assertEquals(List.of(), store.getActionEvents(1));
        assertEquals(List.of(), store.getShotsInRange(0L, Long.MAX_VALUE));
    }

    @Test
    void getActionEvents_includesPointersAndHotkeysAndTypeText_inInsertionOrder() {
        store.putActionPoint(1, 100, 200, "click");
        store.putHotkey(1, "ctrl+s");
        store.putTypeText(1, "hello world");
        store.putActionPoint(1, 300, 400, "double_click");

        List<EvidenceStore.ActionEvent> events = store.getActionEvents(1);
        assertEquals(4, events.size());
        assertTrue(events.get(0).isPointer());
        assertEquals("click", events.get(0).tool());
        assertEquals("hotkey", events.get(1).tool());
        assertEquals("ctrl+s", events.get(1).combo());
        assertFalse(events.get(1).isPointer());
        assertEquals("type_text", events.get(2).tool());
        assertEquals("hello world", events.get(2).text());
        assertTrue(events.get(3).isPointer());
        assertEquals(300, events.get(3).x());
    }

    @Test
    void getActionPoints_filtersOutNonPointerEventsFromTimeline() {
        store.putActionPoint(1, 100, 200, "click");
        store.putHotkey(1, "ctrl+s");
        store.putTypeText(1, "abc");

        List<EvidenceStore.ActionPoint> pts = store.getActionPoints(1);
        assertEquals(1, pts.size(), "only the click should be returned for ring rendering");
        assertEquals(100, pts.get(0).x());
    }

    @Test
    void actionEvent_renderForPrompt_describesEachShape() {
        EvidenceStore.ActionEvent click = new EvidenceStore.ActionEvent(0L, "click", 50, 60, null, null);
        EvidenceStore.ActionEvent hk = new EvidenceStore.ActionEvent(0L, "hotkey", null, null, "ctrl+s", null);
        EvidenceStore.ActionEvent typed = new EvidenceStore.ActionEvent(0L, "type_text", null, null, null, "hi");
        assertTrue(click.renderForPrompt().contains("click@(50,60)"));
        assertTrue(hk.renderForPrompt().contains("combo=ctrl+s"));
        assertTrue(typed.renderForPrompt().contains("text=\"hi\""));
    }
}
