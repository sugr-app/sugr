package com.sugr.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A semantic version, parsed strictly enough to order releases correctly but
 * leniently enough to accept what app authors actually write in
 * {@code sugr.config.json} ({@code "1.0"} and {@code "1"} are treated as
 * {@code 1.0.0}, and a leading {@code v} is ignored).
 *
 * <p>The updater's whole "is this newer than what I'm running?" decision hangs
 * off {@link #compareTo}, so the ordering follows semver.org's rules rather
 * than a naive string compare: numeric identifiers compare numerically
 * ({@code 1.0.10 > 1.0.9}), a prerelease sorts <em>before</em> its release
 * ({@code 1.0.0-beta < 1.0.0}), and build metadata after {@code +} is ignored
 * entirely for ordering.
 */
record SemVer(int major, int minor, int patch, String prerelease, String build) implements Comparable<SemVer> {

    /** What {@link AppMetadata} reports when an app was built without embedded metadata. */
    static final SemVer UNKNOWN = new SemVer(0, 0, 0, "dev", null);

    /**
     * Parses {@code text}, throwing {@link IllegalArgumentException} if it isn't
     * a version at all. Use {@link #parseOrNull} where a bad value should be
     * ignored rather than fail the caller (e.g. a malformed field in a manifest
     * fetched off the network).
     */
    static SemVer parse(String text) {
        SemVer parsed = parseOrNull(text);
        if (parsed == null) {
            throw new IllegalArgumentException("Not a semantic version: " + text);
        }
        return parsed;
    }

    /** Like {@link #parse}, but returns null instead of throwing on unparseable input. */
    static SemVer parseOrNull(String text) {
        if (text == null) {
            return null;
        }
        String rest = text.trim();
        if (rest.startsWith("v") || rest.startsWith("V")) {
            rest = rest.substring(1);
        }
        if (rest.isEmpty()) {
            return null;
        }

        String build = null;
        int plus = rest.indexOf('+');
        if (plus >= 0) {
            build = rest.substring(plus + 1);
            rest = rest.substring(0, plus);
        }

        String prerelease = null;
        int dash = rest.indexOf('-');
        if (dash >= 0) {
            prerelease = rest.substring(dash + 1);
            rest = rest.substring(0, dash);
            if (prerelease.isEmpty()) {
                return null;
            }
        }

        // Accept "1" and "1.2" as well as "1.2.3" - shorter forms pad with zeros.
        String[] numbers = rest.split("\\.", -1);
        if (numbers.length == 0 || numbers.length > 3) {
            return null;
        }
        int[] parts = new int[3];
        for (int i = 0; i < numbers.length; i++) {
            if (numbers[i].isEmpty()) {
                return null;
            }
            try {
                parts[i] = Integer.parseInt(numbers[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (parts[i] < 0) {
                return null;
            }
        }
        return new SemVer(parts[0], parts[1], parts[2], prerelease, build);
    }

    /** True if this is a prerelease (e.g. {@code 1.0.0-beta.2}), which channels use to gate rollouts. */
    boolean isPrerelease() {
        return prerelease != null;
    }

    @Override
    public int compareTo(SemVer other) {
        int cmp = Integer.compare(major, other.major);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(minor, other.minor);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(patch, other.patch);
        if (cmp != 0) {
            return cmp;
        }
        return comparePrerelease(prerelease, other.prerelease);
    }

    /**
     * semver.org rule 11: a version with a prerelease has *lower* precedence
     * than the same version without one, and prereleases compare field by
     * dot-separated field - numeric fields numerically, alphanumeric ones by
     * ASCII, numeric losing to alphanumeric, and a shorter prefix losing to a
     * longer one ({@code 1.0.0-alpha < 1.0.0-alpha.1}).
     */
    private static int comparePrerelease(String left, String right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        List<String> a = split(left);
        List<String> b = split(right);
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int cmp = compareIdentifier(a.get(i), b.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int compareIdentifier(String a, String b) {
        boolean aNumeric = isNumeric(a);
        boolean bNumeric = isNumeric(b);
        if (aNumeric && bNumeric) {
            // Compare as text of equal length so arbitrarily long identifiers
            // can't overflow an int (semver puts no bound on these).
            String left = a.replaceFirst("^0+(?=.)", "");
            String right = b.replaceFirst("^0+(?=.)", "");
            return left.length() != right.length()
                    ? Integer.compare(left.length(), right.length())
                    : left.compareTo(right);
        }
        if (aNumeric) {
            return -1;
        }
        if (bNumeric) {
            return 1;
        }
        return a.compareTo(b);
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> split(String s) {
        List<String> parts = new ArrayList<>();
        for (String part : s.split("\\.", -1)) {
            parts.add(part);
        }
        return parts;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append(major).append('.').append(minor).append('.').append(patch);
        if (prerelease != null) {
            sb.append('-').append(prerelease);
        }
        if (build != null) {
            sb.append('+').append(build);
        }
        return sb.toString();
    }
}
