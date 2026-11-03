package com.dmk.astrocompanion;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Disaster alerts, from two free feeds fetched with the weather (~30 min):
 * <ul>
 * <li>GDACS (UN / EU): tropical cyclones and floods at Orange or Red level. Cyclones count when they are
 * within 3,000 km of you, floods in India or within 1,000 km.</li>
 * <li>SACHET (India's NDMA): the national CAP alert feed, which carries IMD's weather warnings and
 * INCOIS's tsunami bulletins. Weather warnings are kept when they name your district (the nearest big
 * city's), tsunami alerts always.</li>
 * </ul>
 * Shown as one line on the face, a screen of the bottom panel, and a ring on the globe.
 */
final class Alerts {
    static final String GDACS = "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH?eventlist=%s"
            + "&alertlevel=Orange;Red&pageSize=40&pageNumber=1";
    static final String SACHET = "https://sachet.ndma.gov.in/cap_public_website/rss/rss_india.xml";
    static final String KEY = "alerts";
    private static final long H = 3600_000L;

    private Alerts() {}

    /** One alert: kind TC / FL / MET / TSU, score 1..7 (7 worst), label, level or note, position, time, km from you. */
    static final class A {
        final String kind, label, note;
        final int score;
        final double lat, lon;
        final long time;
        final int km;

        A(String kind, int score, String label, String note, double lat, double lon, long time, int km) {
            this.kind = kind;
            this.score = score;
            this.label = label;
            this.note = note;
            this.lat = lat;
            this.lon = lon;
            this.time = time;
            this.km = km;
        }

        String record() {
            return String.format(Locale.US, "%s|%d|%s|%s|%.2f|%.2f|%d|%d", kind, score, clean(label), clean(note), lat, lon,
                    time, km);
        }
    }

    private static String clean(String s) {
        return s.replace(';', ',').replace('|', ' ').replace('\n', ' ').trim();
    }

    // ------------------------------------------------------------ downloads

    /** Fetches both feeds and keeps what applies to you. Returns a status word. */
    static String refresh(SharedPreferences p, SharedPreferences.Editor e, double lat, double lon, long now) {
        List<A> all = new ArrayList<>();
        String st = "";
        try {
            for (String type : new String[]{"TC", "FL"})
                all.addAll(parseGdacs(type, Net.get(String.format(Locale.US, GDACS, type)), lat, lon, now));
            e.putLong("aGdacs", now);
            st += "gdacs ok";
        } catch (Throwable t) {
            Log.w(Store.TAG, "gdacs: " + t);
            st += "gdacs failed";
        }
        try {
            all.addAll(parseSachet(Net.get(SACHET), lat, lon, now));
            e.putLong("aSachet", now);
            st += ", sachet ok";
        } catch (Throwable t) {
            Log.w(Store.TAG, "sachet: " + t);
            st += ", sachet failed";
        }
        StringBuilder b = new StringBuilder();
        for (A a : all) {
            if (b.length() > 0) b.append(';');
            b.append(a.record());
        }
        if (st.contains("gdacs ok")) trackHistory(p, e, all, now);
        // keep the old list when both feeds failed
        if (st.contains("gdacs ok") || st.contains("sachet ok")) e.putString(KEY, b.toString());
        e.putLong("aTime", now);
        return "alerts (" + all.size() + "): " + st;
    }

    /** Cyclone positions kept at every refresh (3 h apart, 6 days) so the globe can draw where each has been. */
    private static void trackHistory(SharedPreferences p, SharedPreferences.Editor e, List<A> now_, long now) {
        java.util.Map<String, List<double[]>> h = history(p, now);
        for (A a : now_) {
            if (!"TC".equals(a.kind)) continue;
            List<double[]> tr = h.get(a.label);
            if (tr == null) {
                tr = new ArrayList<>();
                h.put(a.label, tr);
            }
            if (tr.isEmpty() || now - (long) tr.get(tr.size() - 1)[2] >= 3 * H) tr.add(new double[]{a.lat, a.lon, now});
        }
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, List<double[]>> en : h.entrySet()) {
            for (double[] pt : en.getValue()) {
                if (b.length() > 0) b.append(';');
                b.append(clean(en.getKey())).append('|').append(String.format(Locale.US, "%.2f|%.2f|%d", pt[0], pt[1], (long) pt[2]));
            }
        }
        e.putString("tcHist", b.toString());
    }

    /** Kept cyclone positions by name, oldest first: {lat, lon, time}. */
    static java.util.Map<String, List<double[]>> history(SharedPreferences p, long now) {
        java.util.Map<String, List<double[]>> out = new java.util.LinkedHashMap<>();
        for (String s : p.getString("tcHist", "").split(";")) {
            String[] a = s.split("\\|");
            if (a.length < 4) continue;
            try {
                long t = Long.parseLong(a[3]);
                if (now - t > 6 * 24 * H) continue;
                List<double[]> l = out.get(a[0]);
                if (l == null) {
                    l = new ArrayList<>();
                    out.put(a[0], l);
                }
                l.add(new double[]{Double.parseDouble(a[1]), Double.parseDouble(a[2]), t});
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** GDACS event list (GeoJSON) -> cyclones / floods that matter to you. */
    static List<A> parseGdacs(String type, String json, double lat, double lon, long now) throws Exception {
        List<A> out = new ArrayList<>();
        JSONArray fs = new JSONObject(json).getJSONArray("features");
        for (int i = 0; i < fs.length(); i++) {
            try {
                JSONObject f = fs.getJSONObject(i);
                JSONObject pr = f.getJSONObject("properties");
                if (!"true".equalsIgnoreCase(pr.optString("iscurrent", "false"))) continue;
                String level = pr.optString("alertlevel", "");
                boolean red = "Red".equalsIgnoreCase(level);
                if (!red && !"Orange".equalsIgnoreCase(level)) continue;
                JSONArray c = f.getJSONObject("geometry").getJSONArray("coordinates");
                double elon = c.getDouble(0), elat = c.getDouble(1);
                int km = (int) Math.round(Quakes.km(elat, elon, lat, lon) / 10.0) * 10;
                String country = pr.optString("country", "");
                String name = pr.optString("eventname", pr.optString("name", "")).replaceAll("-\\d+$", "");
                name = plain(name).toUpperCase(Locale.US);
                if ("TC".equals(type)) {
                    if (km > 3000) continue;
                    out.add(new A("TC", red ? 7 : 6, "CYCLONE " + name, red ? "RED" : "ORANGE", elat, elon, now, km));
                } else {
                    if (km > 1000 && !country.toLowerCase(Locale.US).contains("india")) continue;
                    String where = plain(country).toUpperCase(Locale.US);
                    out.add(new A("FL", red ? 5 : 4, "FLOOD " + where, red ? "RED" : "ORANGE", elat, elon, now, km));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** The NDMA RSS feed -> weather warnings naming your district, and tsunami alerts. */
    static List<A> parseSachet(String xml, double lat, double lon, long now) {
        List<A> out = new ArrayList<>();
        String[] city = nearestCity(lat, lon);
        Matcher m = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL).matcher(xml);
        SimpleDateFormat df = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        while (m.find()) {
            String it = m.group(1);
            String title = tag(it, "title");
            long t = now;
            try {
                Date d = df.parse(tag(it, "pubDate"));
                if (d != null) t = d.getTime();
            } catch (Exception ignored) {
            }
            if (now - t > 8 * H || t - now > H) continue;
            String low = title.toLowerCase(Locale.US);
            if (low.contains("tsunami")) {
                if (!low.contains("no threat") && !low.contains("no tsunami") && !low.contains("not expected"))
                    out.add(new A("TSU", 7, "TSUNAMI ALERT", "INCOIS", lat, lon, t, 0));
                continue;
            }
            if (city == null || !low.contains(city[1].toLowerCase(Locale.US))) continue;
            Object[] cl = classify(low);
            if (cl == null) continue;
            out.add(new A("MET", (Integer) cl[0], (String) cl[1], city[0].toUpperCase(Locale.US), lat, lon, t, 0));
        }
        return out;
    }

    /** Severity and name of a weather warning, null for the routine ones (light or moderate rain). */
    static Object[] classify(String low) {
        if (low.contains("extremely heavy")) return new Object[]{6, "EXTREME RAIN"};
        if (low.contains("very heavy")) return new Object[]{5, "VERY HEAVY RAIN"};
        if (low.contains("cyclon")) return new Object[]{6, "CYCLONE"};
        if (low.contains("flood")) return new Object[]{5, "FLOOD"};
        if (low.contains("heavy") && low.contains("rain")) return new Object[]{4, "HEAVY RAIN"};
        if (low.contains("heat wave")) return new Object[]{4, "HEAT WAVE"};
        if (low.contains("cold wave")) return new Object[]{4, "COLD WAVE"};
        if (low.contains("high wave") || low.contains("swell")) return new Object[]{4, "HIGH WAVES"};
        if (low.contains("hail")) return new Object[]{3, "HAIL"};
        if (low.contains("thunder") || low.contains("lightning")) return new Object[]{3, "THUNDER"};
        if (low.contains("gale") || low.contains("squall") || low.contains("dust storm")) return new Object[]{3, "GUSTY WINDS"};
        return null;
    }

    private static String tag(String s, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(s);
        if (!m.find()) return "";
        String v = m.group(1).trim();
        if (v.startsWith("<![CDATA[")) v = v.substring(9, Math.max(9, v.length() - 3));
        return v.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .trim();
    }

    /** Plain capital letters and digits only: the face's font has no accents, and the text length must be exact. */
    private static String plain(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^\\x20-\\x7E]", "").trim();
    }

    // ------------------------------------------------------------ your district

    /** Big cities: name, the word the alerts use for the district, position. The nearest one within 250 km counts as "here". */
    private static final Object[][] CITIES = {
            {"Pune", "Pune", 18.52, 73.86}, {"Mumbai", "Mumbai", 19.08, 72.88}, {"Thane", "Thane", 19.22, 72.98},
            {"Nashik", "Nashik", 20.0, 73.79}, {"Nagpur", "Nagpur", 21.15, 79.09}, {"Kolhapur", "Kolhapur", 16.70, 74.24},
            {"Satara", "Satara", 17.68, 74.0}, {"Ratnagiri", "Ratnagiri", 16.99, 73.30}, {"Raigad", "Raigad", 18.52, 73.18},
            {"Sambhajinagar", "Sambhajinagar", 19.88, 75.34}, {"Solapur", "Solapur", 17.66, 75.91},
            {"Goa", "Goa", 15.50, 73.83}, {"Bengaluru", "Bengaluru", 12.97, 77.59}, {"Mysuru", "Mysuru", 12.30, 76.64},
            {"Mangaluru", "Dakshina Kannada", 12.91, 74.86}, {"Chennai", "Chennai", 13.08, 80.27},
            {"Coimbatore", "Coimbatore", 11.02, 76.96}, {"Madurai", "Madurai", 9.93, 78.12},
            {"Hyderabad", "Hyderabad", 17.39, 78.49}, {"Visakhapatnam", "Visakhapatnam", 17.69, 83.22},
            {"Vijayawada", "Krishna", 16.51, 80.65}, {"Kochi", "Ernakulam", 9.93, 76.27},
            {"Thiruvananthapuram", "Thiruvananthapuram", 8.52, 76.94}, {"Kozhikode", "Kozhikode", 11.26, 75.78},
            {"Kolkata", "Kolkata", 22.57, 88.36}, {"Bhubaneswar", "Khordha", 20.30, 85.82}, {"Puri", "Puri", 19.81, 85.83},
            {"Patna", "Patna", 25.59, 85.14}, {"Ranchi", "Ranchi", 23.34, 85.31}, {"Guwahati", "Kamrup", 26.14, 91.74},
            {"Delhi", "Delhi", 28.61, 77.21}, {"Jaipur", "Jaipur", 26.91, 75.79}, {"Jodhpur", "Jodhpur", 26.28, 73.02},
            {"Ahmedabad", "Ahmedabad", 23.02, 72.57}, {"Surat", "Surat", 21.17, 72.83}, {"Vadodara", "Vadodara", 22.31, 73.18},
            {"Rajkot", "Rajkot", 22.30, 70.80}, {"Bhopal", "Bhopal", 23.26, 77.41}, {"Indore", "Indore", 22.72, 75.86},
            {"Raipur", "Raipur", 21.25, 81.63}, {"Lucknow", "Lucknow", 26.85, 80.95}, {"Varanasi", "Varanasi", 25.32, 82.97},
            {"Kanpur", "Kanpur", 26.45, 80.33}, {"Chandigarh", "Chandigarh", 30.73, 76.78}, {"Amritsar", "Amritsar", 31.63, 74.87},
            {"Dehradun", "Dehradun", 30.32, 78.03}, {"Shimla", "Shimla", 31.10, 77.17}, {"Srinagar", "Srinagar", 34.08, 74.80},
            {"Puducherry", "Puducherry", 11.93, 79.83}};

    /** {name, keyword} of the nearest listed city, null when none is within 250 km. */
    static String[] nearestCity(double lat, double lon) {
        Object[] best = null;
        double bd = 250;
        for (Object[] c : CITIES) {
            double d = Quakes.km((Double) c[2], (Double) c[3], lat, lon);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best == null ? null : new String[]{(String) best[0], (String) best[1]};
    }

    // ------------------------------------------------------------ reading them back

    /** Alerts still current, worst first (then newest). Cyclone distances are worked out from where you are now. */
    static List<A> load(SharedPreferences p, long now, double lat, double lon) {
        List<A> out = new ArrayList<>();
        for (String s : p.getString(KEY, "").split(";")) {
            String[] a = s.split("\\|", 8);
            if (a.length < 8) continue;
            try {
                long t = Long.parseLong(a[6]);
                String kind = a[0];
                double alat = Double.parseDouble(a[4]), alon = Double.parseDouble(a[5]);
                if (("MET".equals(kind) || "TSU".equals(kind)) && now - t > 8 * H) continue;
                if (("TC".equals(kind) || "FL".equals(kind)) && now - p.getLong("aGdacs", 0) > 6 * H) continue;
                int km = "TC".equals(kind) || "FL".equals(kind)
                        ? (int) Math.round(Quakes.km(alat, alon, lat, lon) / 10.0) * 10 : 0;
                out.add(new A(kind, Integer.parseInt(a[1]), a[2], a[3], alat, alon, t, km));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, (x, y) -> x.score != y.score ? Integer.compare(y.score, x.score) : Long.compare(y.time, x.time));
        return out;
    }

    private static String ago(long ms) {
        long m = Math.max(0, ms / 60_000);
        return m < 60 ? m + "M AGO" : (m / 60) + "H AGO";
    }

    /** One line for an alert: "CYCLONE SIMON · 820 km", "HEAVY RAIN · PUNE". */
    static String line(A a) {
        if ("TC".equals(a.kind)) return a.label + " · " + String.format(Locale.US, "%,d km", a.km);
        if ("FL".equals(a.kind)) return a.label + " · " + a.note;
        if ("MET".equals(a.kind)) return a.label + " · " + a.note;
        return a.label;
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** The face's alert line ("alert", empty when all is quiet) and, for the alerts screen, xh / xl / xa / xb. */
    static void values(SharedPreferences p, Map<String, String> v, long now, double lat, double lon, boolean screen) {
        List<A> as = load(p, now, lat, lon);
        v.put("alert", as.isEmpty() ? "" : cut(line(as.get(0)), 26));
        if (!screen) return;
        boolean sOk = now - p.getLong("aSachet", 0) < 6 * H;
        boolean any = p.contains(KEY);
        if (as.isEmpty()) {
            v.put("xh", any ? "NO ALERTS NEARBY" : "ALERTS: NO DATA");
            v.put("xl", "");
            v.put("xa", "GDACS · IMD");
        } else {
            A a = as.get(0);
            v.put("xh", cut(a.kind.equals("TC") || a.kind.equals("FL") || a.kind.equals("TSU") ? a.label : line(a), 17));
            v.put("xl", cut(a.kind.equals("MET") ? ago(now - a.time) : a.kind.equals("TC") ? a.note + " " + a.km + " km" : a.note, 11));
            v.put("xa", as.size() > 1 ? cut(line(as.get(1)), 16) : "NO OTHER ALERTS");
        }
        boolean tsu = false;
        for (A a : as) if ("TSU".equals(a.kind)) tsu = true;
        v.put("xb", tsu ? "TSUNAMI ALERT" : sOk ? "TSUNAMI: NONE" : "TSUNAMI: NO DATA");
    }

    // ------------------------------------------------------------ pictures

    /** Panel picture (428 x 64): one tick per alert over the last 8 hours; cyclones and floods stand at "now". */
    static Bitmap graph(SharedPreferences p, long now, double lat, double lon) {
        final int w = 428, h = 64;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float x0 = 8, x1 = 420, y0 = 7, y1 = 57;
        Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
        axis.setColor(Color.argb(180, 120, 140, 175));
        axis.setStrokeWidth(2);
        c.drawLine(x0, y1, x1, y1, axis);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setStrokeCap(Paint.Cap.ROUND);
        pt.setStrokeWidth(4.5f);
        for (A a : load(p, now, lat, lon)) {
            float x = x1 - (x1 - x0) * (float) Math.min(1, Math.max(0, (now - a.time) / (8.0 * H)));
            float top = y1 - (y1 - y0) * (float) Math.max(0.15, a.score / 7.0);
            pt.setColor(a.score >= 6 ? Color.rgb(255, 84, 100) : a.score >= 4 ? Color.rgb(255, 160, 80) : Color.rgb(255, 210, 120));
            c.drawLine(x, y1, x, top, pt);
            c.drawCircle(x, top, 4, pt);
        }
        return bmp;
    }
}
