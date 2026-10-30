package io.github.rohan9405.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class PickTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    public void progressNeedsBothNumbers() {
        assertEquals(-1, Pick.progress(0, 1000));
        assertEquals(-1, Pick.progress(500, 0));
        assertEquals(50, Pick.progress(500, 1000));
        assertEquals(100, Pick.progress(2000, 1000));
    }

    @Test
    public void oldOrFinishedRowsAreLeftOut() {
        assertTrue(Pick.keepContinue(NOW - DAY, NOW, 40));
        assertTrue(Pick.keepContinue(0, NOW, -1));
        assertFalse(Pick.keepContinue(NOW - 31 * DAY, NOW, 40));
        assertFalse(Pick.keepContinue(NOW - DAY, NOW, 99));
    }

    @Test
    public void yearFromAnyDateShape() {
        assertEquals("2019", Pick.year("2019-05-01"));
        assertEquals("2021", Pick.year("2021"));
        assertEquals("", Pick.year("May 2019"));
        assertEquals("", Pick.year(null));
    }

    @Test
    public void subtitleUsesWhatTheAppFilled() {
        assertEquals("S2 E5 · Lost", Pick.subtitle("2", "5", "Lost"));
        assertEquals("E3", Pick.subtitle("", "3", null));
        assertEquals("Pilot", Pick.subtitle(null, null, "Pilot"));
        assertEquals("", Pick.subtitle(null, null, null));
    }

    @Test
    public void titleCleanedForOmdb() {
        assertEquals("Dark", Pick.cleanTitle("Dark - Season 2"));
        assertEquals("Panchayat", Pick.cleanTitle("Panchayat: Season 3"));
        assertEquals("Dune", Pick.cleanTitle("Dune (2021)"));
        assertEquals("Mirzapur", Pick.cleanTitle("Mirzapur"));
    }

    @Test
    public void noRatingsForVideoAndMusicApps() {
        assertFalse(Pick.rateable("com.google.android.youtube.tv"));
        assertFalse(Pick.rateable("com.spotify.tv.android"));
        assertTrue(Pick.rateable("com.netflix.ninja"));
        assertTrue(Pick.rateable("in.startv.hotstar"));
    }

    @Test
    public void interleaveTakesOneFromEachInTurn() {
        List<List<String>> lists = Arrays.asList(Arrays.asList("a1", "a2"), Arrays.asList("b1"), Arrays.asList("c1", "c2"));
        assertEquals(Arrays.asList("a1", "b1", "c1", "a2", "c2"), Pick.interleave(lists, 10));
        assertEquals(Arrays.asList("a1", "b1", "c1"), Pick.interleave(lists, 3));
    }

    @Test
    public void newVideosAreAWeekOldAtMost() {
        org.junit.Assert.assertTrue(Pick.newVideo("The Daily Show • 43 minutes ago"));
        org.junit.Assert.assertTrue(Pick.newVideo("CNBC-TV18 • Streamed 10 minutes ago"));
        org.junit.Assert.assertTrue(Pick.newVideo("Marvel Entertainment • 5 days ago"));
        org.junit.Assert.assertTrue(Pick.newVideo("NDTV Profit"));   // live now: no age
        org.junit.Assert.assertTrue(Pick.newVideo("Some Channel • 1 week ago"));
        org.junit.Assert.assertFalse(Pick.newVideo("Some Channel • 2 weeks ago"));
        org.junit.Assert.assertFalse(Pick.newVideo("Adnan Dhool Official  • 11 years ago"));
        org.junit.Assert.assertFalse(Pick.newVideo("T-Series • 3 months ago"));
    }
}
