package local.remotevox.trackpadtest;

import java.text.Normalizer;
import java.util.Locale;

/** Search app labels and the phonetic keys provided by the Mac. */
final class AppSearch {
    private AppSearch() {}

    static boolean matches(String name, String pinyin, String initials, String query) {
        String wanted = normalize(query);
        if (wanted.isEmpty()) return true;
        String label = normalize(name);
        String phonetic = normalize(pinyin);
        String abbreviation = normalize(initials);
        if (wanted.length() == 1 && wanted.charAt(0) < 128) {
            return label.startsWith(wanted) || phonetic.startsWith(wanted) || abbreviation.startsWith(wanted);
        }
        return label.contains(wanted) || phonetic.contains(wanted) || abbreviation.contains(wanted)
                || subsequence(wanted, label) || subsequence(wanted, phonetic);
    }

    private static String normalize(String value) {
        String text = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD);
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) result.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static boolean subsequence(String query, String target) {
        int matched = 0;
        for (int index = 0; index < target.length() && matched < query.length(); index++) {
            if (target.charAt(index) == query.charAt(matched)) matched++;
        }
        return matched == query.length();
    }
}
