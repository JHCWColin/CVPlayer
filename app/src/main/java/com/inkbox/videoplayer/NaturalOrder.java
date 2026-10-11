package com.inkbox.videoplayer;

import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

/** 自然排序：数字按数值比较，因此 "第2讲" 排在 "第10讲" 前面。 */
final class NaturalOrder implements Comparator<String> {

    static final Comparator<String> INSTANCE = new NaturalOrder();

    /** 在数字与非数字的边界处切分。 */
    private static final Pattern CHUNK = Pattern.compile("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)");

    private NaturalOrder() {
    }

    @Override
    public int compare(String a, String b) {
        String[] pa = CHUNK.split(a.toLowerCase(Locale.ROOT));
        String[] pb = CHUNK.split(b.toLowerCase(Locale.ROOT));
        int n = Math.min(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String x = pa[i];
            String y = pb[i];
            int c;
            if (Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))) {
                c = compareNumbers(x, y);
            } else {
                c = x.compareTo(y);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(pa.length, pb.length);
    }

    private static int compareNumbers(String x, String y) {
        String a = stripLeadingZeros(x);
        String b = stripLeadingZeros(y);
        if (a.length() != b.length()) {
            return Integer.compare(a.length(), b.length());
        }
        return a.compareTo(b);
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }
}
