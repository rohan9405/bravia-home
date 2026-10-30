package io.github.rohan9405.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public class JustWatchTest {
    private static JSONObject edge(String json) {
        try {
            return new JSONObject(json);
        } catch (org.json.JSONException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void filmKeepsItsFirstReleaseGenresAndBackdrop() {
        Tmdb.Title t = JustWatch.title(edge("{node: {objectType: 'MOVIE', content: {title: 'Christy',"
                + " originalReleaseDate: '2025-11-07', shortDescription: 'A boxer.',"
                + " genres: [{translation: 'Drama'}, {translation: 'Sport'}],"
                + " backdrops: [{backdropUrl: '/backdrop/1/{profile}/christy.{format}'}],"
                + " externalIds: {tmdbId: '1287322'}}}}"));
        assertEquals("movie", t.kind);
        assertEquals("Christy", t.name);
        assertEquals("2025", t.year);
        assertEquals("2025-11-07", t.released);
        assertEquals("Drama, Sport", t.genres);
        assertEquals(1287322, t.id);
        assertEquals("https://images.justwatch.com/backdrop/1/s1440/christy.jpg", t.backdrop);
        assertEquals("", t.season);
    }

    @Test
    public void aLaterSeasonIsTheShowWithItsSeason() {
        Tmdb.Title t = JustWatch.title(edge("{node: {objectType: 'SHOW_SEASON', content: {title: 'Season 2',"
                + " originalReleaseDate: '2026-09-30', genres: [], backdrops: [], externalIds: {tmdbId: '295778:2'}},"
                + " show: {content: {title: 'Scrubs', backdrops: [{backdropUrl: '/backdrop/9/{profile}/scrubs.{format}'}],"
                + " externalIds: {tmdbId: '295778'}}}}}"));
        assertEquals("tv", t.kind);
        assertEquals("Scrubs", t.name);
        assertEquals("Season 2", t.season);
        assertEquals("2026-09-30", t.released);
        assertEquals(295778, t.id);
        assertEquals("https://images.justwatch.com/backdrop/9/s1440/scrubs.jpg", t.backdrop);   // the show's, the season has none
    }

    @Test
    public void aFirstSeasonIsJustTheNewSeries() {
        Tmdb.Title t = JustWatch.title(edge("{node: {objectType: 'SHOW_SEASON', content: {title: 'Season 1',"
                + " originalReleaseDate: '2026-09-04'}, show: {content: {title: 'The Court'}}}}"));
        assertEquals("The Court", t.name);
        assertEquals("", t.season);
    }

    @Test
    public void nothingWithoutATitleOrDateOrOfAnotherType() {
        assertNull(JustWatch.title(edge("{node: {objectType: 'MOVIE', content: {title: 'No date'}}}")));
        assertNull(JustWatch.title(edge("{node: {objectType: 'EPISODE', content: {title: 'x', originalReleaseDate: '2026-10-01'}}}")));
        assertNull(JustWatch.title(edge("{}")));
    }
}
