package io.github.rohan9405.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;

import org.junit.Test;

/** The same cases as StreamRatings' cache_policy.js. */
public class CachePolicyTest {
    private static final LocalDate NOW = LocalDate.of(2026, 10, 29);

    @Test
    public void notFoundIsRetriedAfterFiveDays() {
        assertEquals(5, CachePolicy.ttlDays(false, "2020", "1 Jan 2020", NOW));
    }

    @Test
    public void byReleaseDate() {
        assertEquals(3, CachePolicy.ttlDays(true, "2026", "10 Oct 2026", NOW));    // this month
        assertEquals(3, CachePolicy.ttlDays(true, "2026", "15 Aug 2026", NOW));    // 2 months
        assertEquals(5, CachePolicy.ttlDays(true, "2026", "1 Jul 2026", NOW));     // 3 months
        assertEquals(5, CachePolicy.ttlDays(true, "2025", "1 Dec 2025", NOW));     // 10 months
        assertEquals(15, CachePolicy.ttlDays(true, "2025", "1 Oct 2025", NOW));    // 12 months
        assertEquals(15, CachePolicy.ttlDays(true, "2022", "5 Mar 2022", NOW));
        assertEquals(30, CachePolicy.ttlDays(true, "2017", "22 Sep 2017", NOW));
    }

    @Test
    public void seriesStillAiringCountsAsRecent() {
        assertEquals(5, CachePolicy.ttlDays(true, "2016–", "15 Jul 2016", NOW));
        assertEquals(5, CachePolicy.ttlDays(true, "2016-", "N/A", NOW));
    }

    @Test
    public void endedSeriesGoesByItsLastYear() {
        assertEquals(15, CachePolicy.ttlDays(true, "2019–2023", "1 Mar 2019", NOW));   // ended Dec 2023: 34 months
        assertEquals(30, CachePolicy.ttlDays(true, "2010–2015", "N/A", NOW));
    }

    @Test
    public void onlyAYearMeansItsLastDay() {
        assertEquals(3, CachePolicy.ttlDays(true, "2026", "N/A", NOW));   // Dec 31 is ahead: capped at today
        assertEquals(15, CachePolicy.ttlDays(true, "2024", "", NOW));      // Dec 2024: 22 months
    }

    @Test
    public void noDateAtAllIsFiveDays() {
        assertEquals(5, CachePolicy.ttlDays(true, "", "N/A", NOW));
    }

    @Test
    public void freshUntilTheTtlRunsOut() {
        long now = 1_800_000_000_000L;
        assertTrue(CachePolicy.fresh(now - 2 * CachePolicy.DAY_MS, 3, now));
        assertFalse(CachePolicy.fresh(now - 3 * CachePolicy.DAY_MS, 3, now));
        assertFalse(CachePolicy.fresh(0, 30, now));
    }
}
