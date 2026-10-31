package io.github.rohan9405.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.json.JSONObject;
import org.junit.Test;

public class InsightsTest {
    private static JSONObject json(String s) {
        try {
            return new JSONObject(s);
        } catch (org.json.JSONException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void portfolioIsTheThreeClassesAddedUpAndTheIndices() throws Exception {
        JSONObject mf = json("{asset_summary: {total_value: 6872204.75, one_day_change: -21430.73, one_day_change_percentage: -0.31}}");
        JSONObject in = json("{asset_summary: {total_value: 5611289.65, one_day_change: 27279.22, one_day_change_percentage: 0.49}}");
        JSONObject us = json("{asset_summary: {total_value: 670393.98, one_day_change: 163.01, one_day_change_percentage: 0.02, one_day_change_usd: 1.72}}");
        JSONObject idx = json("{INDI00012: {entity_basic: {display_name: 'NIFTY 50'}, entity_stats: {live_price: 23053.1, day_change: 116.35,"
                + " day_change_percentage: 0.51, prev_close: 22936.75, price_updated_at_ist: '2026-10-30T15:45:00+05:30'}},"
                + " INDI00050: {entity_basic: {display_name: 'SENSEX'}, entity_stats: {live_price: 74459.53, day_change: 308.49,"
                + " day_change_percentage: 0.42, prev_close: 74151.04, price_updated_at_ist: '2026-10-30T16:00:00+05:30'}}}");
        JSONObject out = Insights.compose(mf, in, us, idx);
        JSONObject p = out.getJSONObject("portfolio");
        assertEquals(6011.5, p.getDouble("change"), 0.01);
        assertEquals(6011.5 * 100 / (13153888.38 - 6011.5), p.getDouble("pct"), 0.0001);
        assertEquals(3, p.getJSONArray("classes").length());
        assertEquals(1.72, p.getJSONArray("classes").getJSONObject(2).getDouble("change_usd"), 0.001);
        assertEquals("NIFTY 50", out.getJSONArray("markets").getJSONObject(0).getString("name"));
        assertEquals(0.42, out.getJSONArray("markets").getJSONObject(1).getDouble("pct"), 0.001);
    }

    @Test
    public void aClassWithoutItsDayIsLeftOutNotCountedAsZero() throws Exception {
        JSONObject out = Insights.compose(json("{source_status: {holdings: 'error'}}"), json("{}"), json("{}"), json("{}"));
        assertFalse(out.has("portfolio"));
        assertEquals(0, out.getJSONArray("markets").length());
    }

    @Test
    public void fetchedEveryQuarterHourWhileTheMarketIsOpenElseHourly() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        assertEquals(15 * 60_000L, Insights.maxAgeMs(ZonedDateTime.of(2026, 10, 30, 11, 0, 0, 0, ist)));   // Friday 11:00
        assertEquals(60 * 60_000L, Insights.maxAgeMs(ZonedDateTime.of(2026, 10, 30, 16, 0, 0, 0, ist)));   // after the close
        assertEquals(60 * 60_000L, Insights.maxAgeMs(ZonedDateTime.of(2026, 10, 31, 11, 0, 0, 0, ist)));   // Saturday
    }
}
