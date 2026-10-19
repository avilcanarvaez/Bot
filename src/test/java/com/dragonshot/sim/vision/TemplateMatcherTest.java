package com.dragonshot.sim.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonshot.sim.capture.Frame;
import com.dragonshot.sim.testutil.TestImages;
import org.junit.jupiter.api.Test;

class TemplateMatcherTest {

    @Test
    void findsExactPatch() {
        Frame patch = TestImages.randomTexture(40, 40, 11);
        Frame base = TestImages.randomTexture(240, 180, 7);
        Frame haystack = TestImages.paste(base, patch, 90, 70);
        TemplateMatcher.MatchResult result = TemplateMatcher.match(
                haystack, patch, 50, 30, 130, 130, new double[]{1.0}, 0.5);
        assertTrue(result.found());
        assertEquals(90, result.x(), 1.0);
        assertEquals(70, result.y(), 1.0);
        assertTrue(result.score() > 0.95);
    }

    @Test
    void findsPatchAfterScenarioTranslation() {
        Frame patch = TestImages.randomTexture(40, 40, 19);
        Frame base = TestImages.randomTexture(240, 180, 3);
        Frame original = TestImages.paste(base, patch, 90, 70);
        Frame shifted = TestImages.translate(original, 7, -5, 20);
        TemplateMatcher.MatchResult result = TemplateMatcher.match(
                shifted, patch, 60, 40, 120, 120, new double[]{1.0}, 0.5);
        assertTrue(result.found());
        assertEquals(97, result.x(), 1.0);
        assertEquals(65, result.y(), 1.0);
    }

    @Test
    void rejectsUniformTemplate() {
        Frame haystack = TestImages.randomTexture(120, 120, 5);
        Frame patch = TestImages.blank(30, 30, 100);
        TemplateMatcher.MatchResult result = TemplateMatcher.match(
                haystack, patch, 0, 0, 120, 120, new double[]{1.0}, 0.5);
        assertFalse(result.found());
    }

    @Test
    void findMultiScaleTemplate() {
        Frame patch = TestImages.randomTexture(30, 30, 23);
        Frame scaled = TestImages.resize(patch, 33, 33);
        Frame base = TestImages.randomTexture(220, 160, 31);
        Frame haystack = TestImages.paste(base, scaled, 80, 60);
        TemplateMatcher.MatchResult result = TemplateMatcher.match(
                haystack, patch, 55, 35, 100, 100,
                new double[]{0.95, 1.0, 1.05, 1.1, 1.15}, 0.45);
        assertTrue(result.found());
        assertEquals(1.1, result.scale(), 0.001);
        assertEquals(80, result.x(), 1.5);
        assertEquals(60, result.y(), 1.5);
    }

    @Test
    void anchorTrackerReportsDeltaAndLostState() {
        Frame patch = TestImages.randomTexture(36, 36, 41);
        Frame base = TestImages.randomTexture(260, 200, 43);
        Frame original = TestImages.paste(base, patch, 70, 90);
        Frame shifted = TestImages.translate(original, -9, 6, 15);

        AnchorTracker tracker = new AnchorTracker(80);
        tracker.setTemplate(patch, 70, 90);
        AnchorTracker.Update update = tracker.update(shifted);
        assertTrue(update.found());
        assertEquals(-9, update.dx(), 1.0);
        assertEquals(6, update.dy(), 1.0);
        assertEquals(0, update.lostFrames());

        AnchorTracker empty = new AnchorTracker(80);
        AnchorTracker.Update missing = empty.update(original);
        assertFalse(missing.found());
        assertEquals(1, missing.lostFrames());
    }
}
