package io.github.rohan9405.feed;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class HeroRankTest {
    private static Item hero(String app, String title, String rating, double popularity) {
        Item i = new Item();
        i.section = Item.HERO;
        i.tab = app;
        i.title = title;
        i.rating = rating;
        i.popularity = popularity;
        return i;
    }

    @Test
    public void bestRatingFirstThenPopularity() {
        List<Item> ranked = Pick.rank(Arrays.asList(hero("n", "a", "7.1", 5), hero("n", "b", "", 99),
                hero("n", "c", "8.4", 1), hero("n", "d", "", 50)));
        assertEquals("c", ranked.get(0).title);
        assertEquals("a", ranked.get(1).title);
        assertEquals("b", ranked.get(2).title);   // unrated, more popular
        assertEquals("d", ranked.get(3).title);
    }

    @Test
    public void thisYearsTitlesFirstThenNewSeasonsThenOlder() {
        Item old = hero("n", "old", "9.5", 50);
        old.year = "2025";
        Item season = hero("n", "season", "9.0", 50);
        season.year = "2026";
        season.subtitle = "Season 4";
        Item fresh = hero("n", "fresh", "7.0", 1);
        fresh.year = "2026";
        List<Item> r = Pick.rankHero(Arrays.asList(old, season, fresh), 2026);
        assertEquals(Arrays.asList("fresh", "season", "old"), Arrays.asList(r.get(0).title, r.get(1).title, r.get(2).title));
    }

    @Test
    public void recentByFirstReleaseDateNotStreamingDate() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 30);
        assertEquals(true, Pick.recentEnough("2026-10-23", "", today));     // Gajab Raat
        assertEquals(true, Pick.recentEnough("2025-11-15", "", today));     // within a year
        assertEquals(false, Pick.recentEnough("2025-09-19", "", today));    // Kurla To Vengurla: 13 months
        assertEquals(false, Pick.recentEnough("2016-09-09", "", today));    // Baar Baar Dekho
        assertEquals(false, Pick.recentEnough("2023-10-27", "", today));    // Sumo Didi
        assertEquals(true, Pick.recentEnough("2026-10-20", "Season 23", today));
        assertEquals(false, Pick.recentEnough("2026-11-20", "", today));    // not out yet
        assertEquals(false, Pick.recentEnough("", "", today));
    }

    @Test
    public void sameTierAndRatingNewerFirst() {
        Item older = hero("n", "older", "8.0", 10);
        older.year = "2026";
        older.released = "2026-10-10";
        Item newer = hero("n", "newer", "8.0", 1);
        newer.year = "2026";
        newer.released = "2026-10-25";
        assertEquals("newer", Pick.rankHero(Arrays.asList(older, newer), 2026).get(0).title);
    }

    @Test
    public void twentyFivePerAppAndOtherSectionsUntouched() {
        List<Item> in = new ArrayList<>();
        Item cont = new Item();
        cont.section = Item.CONTINUE;
        in.add(cont);
        for (int i = 0; i < 30; i++) in.add(hero("netflix", "n" + i, String.valueOf(5 + i * 0.1), 0));
        for (int i = 0; i < 3; i++) in.add(hero("prime", "p" + i, "", i));
        List<Item> out = Refresher.keepHeroTop(in);
        assertEquals(1 + 25 + 3, out.size());
        assertEquals(Item.CONTINUE, out.get(0).section);
        assertEquals("n29", out.get(1).title);
        assertEquals(1, out.get(1).rowOrder);
        assertEquals("p2", out.get(26).title);
    }
}
