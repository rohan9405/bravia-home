package io.github.rohan9405.feed;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How long a cached rating stays fresh, the same rule as StreamRatings' cache_policy.js: newer titles' ratings move
 * faster, so they are asked for again sooner.
 *
 * <pre>
 *   released in the last 3 months (incl. current month) ->  3 days
 *   released 3-12 months ago                            ->  5 days
 *   released 1-5 years ago                              -> 15 days
 *   released more than 5 years ago                      -> 30 days
 * </pre>
 *
 * Series still airing (Year like "2016–") count as 5 days, since new seasons keep moving their rating. Titles OMDb
 * could not find are retried after 5 days, and so is a title with no date at all.
 */
final class CachePolicy {
    static final long DAY_MS = 24L * 60 * 60 * 1000;
    static final int NOT_FOUND_TTL_DAYS = 5;

    private static final Pattern RANGE = Pattern.compile("^(\\d{4})\\s*[–-]\\s*(\\d{4})?$");
    private static final Pattern OPEN_RANGE = Pattern.compile("^\\d{4}\\s*[–-]\\s*$");
    private static final Pattern YEAR = Pattern.compile("\\b(\\d{4})\\b");
    /** OMDb's "Released": "22 Sep 2017". */
    private static final DateTimeFormatter RELEASED = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private CachePolicy() {
    }

    /** Days a result stays fresh: OMDb's "Year" ("2017", "2016–", "2019–2023") and "Released" fields. */
    static int ttlDays(boolean found, String year, String released, LocalDate now) {
        if (!found) return NOT_FOUND_TTL_DAYS;
        String y = year == null ? "" : year.trim();
        if (OPEN_RANGE.matcher(y).matches()) return 5;   // still airing
        LocalDate date = releaseDate(y, released, now);
        if (date == null) return 5;
        // Whole calendar months between release and now (current month = 0).
        int monthsAgo = (now.getYear() * 12 + now.getMonthValue()) - (date.getYear() * 12 + date.getMonthValue());
        if (monthsAgo < 3) return 3;
        if (monthsAgo < 12) return 5;
        if (monthsAgo < 60) return 15;
        return 30;
    }

    static boolean fresh(long savedMs, int ttlDays, long nowMs) {
        return savedMs > 0 && nowMs - savedMs < ttlDays * DAY_MS;
    }

    /** The best-known release date, or null. */
    static LocalDate releaseDate(String year, String released, LocalDate now) {
        Matcher range = RANGE.matcher(year);
        if (range.matches()) {
            if (range.group(2) == null) return now;   // still airing: treated as recent
            return yearEnd(Integer.parseInt(range.group(2)), now);
        }
        if (released != null && !released.isEmpty() && !"N/A".equals(released)) {
            try {
                return LocalDate.parse(released.trim(), RELEASED);
            } catch (DateTimeParseException ignored) {
                // Fall back to the year.
            }
        }
        Matcher m = YEAR.matcher(year);
        return m.find() ? yearEnd(Integer.parseInt(m.group(1)), now) : null;
    }

    /** Only a year is known: assume its last day (capped at today), so a title is never refreshed too rarely. */
    private static LocalDate yearEnd(int year, LocalDate now) {
        LocalDate end = LocalDate.of(year, 12, 31);
        return end.isAfter(now) ? now : end;
    }
}
