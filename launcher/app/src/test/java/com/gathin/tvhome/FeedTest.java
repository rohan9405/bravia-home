package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class FeedTest {
    private static Feed.Entry e(String section, String tab, int order, String title, String rating) {
        return new Feed.Entry(section, tab, order, title, "", rating, -1, "", "", "x", tab, "", "", "", "");
    }

    private static Feed.Entry g(String tab, String title, String rating, String genre) {
        return new Feed.Entry(Feed.APP, tab, 1, title, "", rating, -1, "", "", "x", tab, "", "tv", "", genre);
    }

    @Test
    public void genreRowsPoolDrawerAppsOnceEachBestFirst() {
        Map<String, List<Feed.Entry>> tabs = new java.util.LinkedHashMap<>();
        tabs.put("n", Arrays.asList(g("n", "A", "7.0", "Drama, Crime"), g("n", "B", "9.0", "Drama"), g("n", "C", "8.0", "Comedy")));
        tabs.put("p", Arrays.asList(g("p", "D", "8.5", "Drama"), g("p", "a", "6.0", "Drama"), g("p", "E", "7.5", "Comedy, Drama")));
        tabs.put("x", Arrays.asList(g("x", "F", "9.9", "Drama")));   // not in the drawer
        Map<String, List<Feed.Entry>> rows = Feed.genreRows(tabs, new java.util.HashMap<>(), Arrays.asList("n", "p"), 10, 8, 2);
        assertEquals(Arrays.asList("Drama", "Comedy"), new ArrayList<>(rows.keySet()));   // Crime has 1 title: no tab
        List<Feed.Entry> drama = rows.get("Drama");
        assertEquals(4, drama.size());   // A, B, D, E: "a" is A again
        assertEquals("B", drama.get(0).title);   // n's best
        assertEquals("D", drama.get(1).title);   // then p's best
        assertEquals("A", drama.get(2).title);   // then n's next
    }

    @Test
    public void genreRowsTakeAppsInTurnFromTheirPools() {
        Map<String, List<Feed.Entry>> pool = new java.util.LinkedHashMap<>();
        pool.put("n", Arrays.asList(g("n", "N1", "9.0", "Sci-Fi & Fantasy"), g("n", "N2", "8.9", "Science Fiction"),
                g("n", "N3", "8.8", "Sci-Fi")));
        pool.put("p", Arrays.asList(g("p", "P1", "6.0", "Science Fiction")));
        Map<String, List<Feed.Entry>> rows = Feed.genreRows(new java.util.HashMap<>(), pool, Arrays.asList("n", "p"), 10, 8, 3);
        List<Feed.Entry> scifi = rows.get("Sci-Fi");
        assertEquals("N1", scifi.get(0).title);
        assertEquals("P1", scifi.get(1).title);   // the other app's turn, though its rating is lower
        assertEquals("N2", scifi.get(2).title);
    }

    @Test
    public void pairedGenresMergeIntoOne() {
        Map<String, List<Feed.Entry>> tabs = new java.util.LinkedHashMap<>();
        tabs.put("n", Arrays.asList(g("n", "A", "7.0", "Action & Adventure"), g("n", "B", "8.0", "Action"),
                g("n", "C", "6.0", "Action, Action & Adventure")));
        Map<String, List<Feed.Entry>> rows = Feed.genreRows(tabs, new java.util.HashMap<>(), Arrays.asList("n"), 10, 8, 1);
        assertEquals(Arrays.asList("Action"), new ArrayList<>(rows.keySet()));
        assertEquals(3, rows.get("Action").size());   // C once, though it named both
    }

    @Test
    public void removedTitlesComeBackOnlyWhenWatchedAgain() {
        // Removed when last watched at 1000, 40% in.
        assertTrue(Feed.stillRemoved(1000, 40, 1000, 40));     // the same row after a restart: stays gone
        assertFalse(Feed.stillRemoved(1000, 40, 2000, 55));    // watched again: back
        assertTrue(Feed.stillRemoved(1000, 40, 900, 40));      // an older copy of the row: stays gone
        // An app that gives no time: back only when the position moves.
        assertTrue(Feed.stillRemoved(0, 40, 0, 40));
        assertFalse(Feed.stillRemoved(0, 40, 0, 62));
    }

    @Test
    public void tabsKeepRankOrderAndDropUnusable() {
        List<Feed.Entry> in = Arrays.asList(e(Feed.APP, "p", 2, "two", ""), e(Feed.APP, "p", 1, "one", ""),
                e(Feed.APP, "p", 3, "", ""), e(Feed.CONTINUE, "p", 0, "cont", ""), e(Feed.APP, "", 1, "noTab", ""));
        Map<String, List<Feed.Entry>> tabs = Feed.group(in, Feed.APP);
        assertEquals(1, tabs.size());
        assertEquals("one", tabs.get("p").get(0).title);
        assertEquals("two", tabs.get("p").get(1).title);
    }

    private static List<Feed.Entry> ranked(String pkg, int n) {
        List<Feed.Entry> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(e(Feed.HERO, pkg, i + 1, pkg + i, String.valueOf(9 - i * 0.1)));
        return l;
    }

    @Test
    public void heroTakesFivePerDrawerAppInTurnThenFillsByRating() {
        Map<String, List<Feed.Entry>> c = new java.util.LinkedHashMap<>();
        c.put("netflix", ranked("netflix", 10));
        c.put("prime", ranked("prime", 2));
        c.put("apple", ranked("apple", 8));
        c.put("notInDrawer", ranked("notInDrawer", 5));
        c.put(Feed.YOUTUBE, ranked(Feed.YOUTUBE, 5));
        List<Feed.Entry> out = Feed.pickHero(c, Arrays.asList("netflix", Feed.YOUTUBE, "prime", "apple"), 5, 20, 42);
        // 5 + 2 + 5 in turn, then the 5 + 3 left over from netflix and apple: 20 asked, 20 available.
        assertEquals(20, out.size());
        assertEquals("netflix0", out.get(0).title);
        assertEquals("prime0", out.get(1).title);
        assertEquals("apple0", out.get(2).title);
        for (Feed.Entry x : out) {
            assertFalse(x.tab.equals(Feed.YOUTUBE));
            assertFalse(x.tab.equals("notInDrawer"));
        }
        assertEquals("netflix1", out.get(3).title);   // each app's best two always show
        assertEquals("apple1", out.get(5).title);
        // The top-up comes best-rated first.
        for (int i = 13; i < out.size(); i++) assertTrue(out.get(i - 1).ratingValue() >= out.get(i).ratingValue());
    }

    @Test
    public void heroDrawChangesWithTheRefreshOnly() {
        Map<String, List<Feed.Entry>> c = new java.util.LinkedHashMap<>();
        c.put("netflix", ranked("netflix", 25));
        List<String> drawer = Arrays.asList("netflix");
        List<Feed.Entry> a = Feed.pickHero(c, drawer, 5, 5, 1000), again = Feed.pickHero(c, drawer, 5, 5, 1000);
        assertEquals(titles(a), titles(again));   // Home redrawing: the same five
        boolean changed = false;
        for (long seed = 1001; seed < 1010 && !changed; seed++) changed = !titles(Feed.pickHero(c, drawer, 5, 5, seed)).equals(titles(a));
        assertTrue(changed);   // a new refresh: a new mix
        assertEquals("netflix0", a.get(0).title);
        assertEquals("netflix1", a.get(1).title);
    }

    @Test
    public void oneAppHeaderIsItsBestFiveThenTenDrawn() {
        List<Feed.Entry> netflix = ranked("netflix", 30);
        List<Feed.Entry> out = Feed.pickApp(netflix, 15, 5, 7);
        assertEquals(15, out.size());
        for (int i = 0; i < 5; i++) assertEquals("netflix" + i, out.get(i).title);
        for (Feed.Entry e : out) assertEquals("netflix", e.tab);
        assertEquals(titles(out), titles(Feed.pickApp(netflix, 15, 5, 7)));
        assertEquals(2, Feed.pickApp(ranked("apple", 2), 15, 5, 7).size());   // fewer than 15: all it has
    }

    private static List<String> titles(List<Feed.Entry> l) {
        List<String> out = new ArrayList<>();
        for (Feed.Entry e : l) out.add(e.title);
        return out;
    }

    @Test
    public void heroShowsATitleOnTwoServicesOnce() {
        Map<String, List<Feed.Entry>> c = new java.util.LinkedHashMap<>();
        c.put("prime", Arrays.asList(e(Feed.HERO, "prime", 1, "The Awakening", "7.0")));
        c.put("hotstar", Arrays.asList(e(Feed.HERO, "hotstar", 1, "The Awakening", "7.0"), e(Feed.HERO, "hotstar", 2, "The Mob", "6.0")));
        List<Feed.Entry> out = Feed.pickHero(c, Arrays.asList("prime", "hotstar"), 5, 20, 0);
        assertEquals(2, out.size());
        assertEquals("prime", out.get(0).tab);
        assertEquals("The Mob", out.get(1).title);
    }

    @Test
    public void heroStopsAtTotal() {
        Map<String, List<Feed.Entry>> c = new java.util.HashMap<>();
        c.put("a", ranked("a", 10));
        c.put("b", ranked("b", 10));
        c.put("c", ranked("c", 10));
        c.put("d", ranked("d", 10));
        c.put("e", ranked("e", 10));
        assertEquals(20, Feed.pickHero(c, Arrays.asList("a", "b", "c", "d", "e"), 5, 20, 0).size());
    }
}
