package io.github.rohan9405.feed;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class DetailsTest {
    private static JSONObject json(String s) {
        try {
            return new JSONObject(s);
        } catch (org.json.JSONException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void filmCastDirectorRuntimeAndIndianRating() {
        Tmdb.Details d = Tmdb.parseDetails("movie", json("{overview: 'A wish.', tagline: 'Careful.', release_date: '2026-05-13',"
                + " runtime: 109, original_language: 'en', spoken_languages: [{iso_639_1: 'en', english_name: 'English'}],"
                + " release_dates: {results: [{iso_3166_1: 'US', release_dates: [{certification: 'R'}]},"
                + "   {iso_3166_1: 'IN', release_dates: [{certification: ''}, {certification: 'A'}]}]},"
                + " credits: {cast: [{name: 'Michael Johnston'}, {name: 'Inde Navarrette'}, {name: 'Michael Johnston'}],"
                + "   crew: [{name: 'Sam Writer', job: 'Writer'}, {name: 'Curry Barker', job: 'Director'}]}}"));
        assertEquals("A", d.certification);   // India's, not the US one
        assertEquals("Michael Johnston, Inde Navarrette", d.cast);
        assertEquals("Curry Barker", d.directors);
        assertEquals(109, d.runtime);
        assertEquals("English", d.language);
        assertEquals("Careful.", d.tagline);
        assertEquals("", d.creators);
    }

    @Test
    public void seriesSeasonsCreatorsAndEpisodeLength() {
        Tmdb.Details d = Tmdb.parseDetails("tv", json("{first_air_date: '2026-09-04', status: 'Returning Series',"
                + " episode_run_time: [], last_episode_to_air: {runtime: 42}, number_of_seasons: 2, number_of_episodes: 16,"
                + " original_language: 'hi', created_by: [{name: 'A Creator'}],"
                + " content_ratings: {results: [{iso_3166_1: 'IN', rating: 'U/A 16+'}]},"
                + " aggregate_credits: {cast: [{name: 'Lead One'}, {name: 'Lead Two'}]}}"));
        assertEquals(42, d.episodeRuntime);
        assertEquals(2, d.seasons);
        assertEquals(16, d.episodes);
        assertEquals("A Creator", d.creators);
        assertEquals("U/A 16+", d.certification);
        assertEquals("Lead One, Lead Two", d.cast);
        assertEquals("Hindi", d.language);   // no spoken_languages: Java's name for "hi"
        assertEquals("Returning Series", d.status);
    }
}
