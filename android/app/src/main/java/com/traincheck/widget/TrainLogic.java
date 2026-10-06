package com.traincheck.widget;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** shared/core.js の nextDepartures と同じ計算（Java版） */
public final class TrainLogic {
    public static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final int DAY = 24 * 60;
    private static final int SERVICE_DAY_START_HOUR = 3;
    private static final Pattern ITEM = Pattern.compile("^(\\d{1,2})(.*)$", Pattern.DOTALL);

    // core.js と同じ祝日一覧（2026〜2029年）
    private static final Set<String> HOLIDAYS = new HashSet<>(Arrays.asList(
        "2026-01-01","2026-01-12","2026-02-11","2026-02-23","2026-03-20","2026-04-29","2026-05-03","2026-05-04","2026-05-05","2026-05-06","2026-07-20","2026-08-11","2026-09-21","2026-09-22","2026-09-23","2026-10-12","2026-11-03","2026-11-23",
        "2027-01-01","2027-01-11","2027-02-11","2027-02-23","2027-03-21","2027-03-22","2027-04-29","2027-05-03","2027-05-04","2027-05-05","2027-07-19","2027-08-11","2027-09-20","2027-09-23","2027-10-11","2027-11-03","2027-11-23",
        "2028-01-01","2028-01-10","2028-02-11","2028-02-23","2028-03-20","2028-04-29","2028-05-03","2028-05-04","2028-05-05","2028-07-17","2028-08-11","2028-09-18","2028-09-22","2028-10-09","2028-11-03","2028-11-23",
        "2029-01-01","2029-01-08","2029-02-11","2029-02-12","2029-02-23","2029-03-20","2029-04-29","2029-04-30","2029-05-03","2029-05-04","2029-05-05","2029-07-16","2029-08-11","2029-09-17","2029-09-23","2029-09-24","2029-10-08","2029-11-03","2029-11-23"));

    private TrainLogic() {}

    public static final class Departure {
        public String time, mark, kind, arrive, dayType;
        public int departsIn, leaveIn, depAbs;
        public Integer arriveAbs;
        public boolean nextServiceDay;
        /** 乗り換えありのルートのときだけ入る */
        public List<Leg> legs;
        /** 出発地を出るべき時刻（epoch ミリ秒） */
        public long leaveAtMillis;
    }

    public static final class Result {
        public List<Departure> departures = new ArrayList<>();
        public String dayType, now;
        public int walkMinutes;
    }

    public static String dayLabel(String type) {
        switch (type) {
            case "saturday": return "土曜";
            case "holiday": return "土休日";
            default: return "平日";
        }
    }

    static String pad(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    static String fmt(int min) {
        int m = ((min % DAY) + DAY) % DAY;
        return (m / 60) + ":" + pad(m % 60);
    }

    @SuppressWarnings("unchecked")
    static String dayType(LocalDate d, Map<String, Object> config) {
        String iso = d.toString();
        String md = pad(d.getMonthValue()) + "-" + pad(d.getDayOfMonth());
        List<Object> extra = config != null && config.get("extraHolidays") instanceof List
            ? (List<Object>) config.get("extraHolidays") : Collections.emptyList();
        if (d.getDayOfWeek() == DayOfWeek.SUNDAY || HOLIDAYS.contains(iso) || extra.contains(md)) return "holiday";
        if (d.getDayOfWeek() == DayOfWeek.SATURDAY) return "saturday";
        return "weekday";
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> tableFor(Map<String, Object> route, String type) {
        Map<String, Object> tt = route.get("timetable") instanceof Map ? (Map<String, Object>) route.get("timetable") : Collections.emptyMap();
        Object w = tt.get("weekday"), s = tt.get("saturday"), h = tt.get("holiday");
        Object pick;
        if (type.equals("saturday")) pick = s != null ? s : (h != null ? h : w);
        else if (type.equals("holiday")) pick = h != null ? h : w;
        else pick = w;
        return pick instanceof Map ? (Map<String, Object>) pick : null;
    }

    /** {"6":[5,"15快"]} → [[分, 印]] を時刻順で */
    @SuppressWarnings("unchecked")
    static List<Object[]> parseTable(Map<String, Object> table) {
        List<Object[]> out = new ArrayList<>();
        if (table == null) return out;
        for (Map.Entry<String, Object> e : table.entrySet()) {
            int h;
            try { h = Integer.parseInt(e.getKey().trim()); } catch (NumberFormatException ex) { continue; }
            if (!(e.getValue() instanceof List)) continue;
            for (Object item : (List<Object>) e.getValue()) {
                String str = item instanceof Double ? String.valueOf(((Double) item).intValue()) : String.valueOf(item);
                Matcher m = ITEM.matcher(str);
                if (!m.matches()) continue;
                out.add(new Object[]{h * 60 + Integer.parseInt(m.group(1)), m.group(2).trim()});
            }
        }
        out.sort((a, b) -> Integer.compare((Integer) a[0], (Integer) b[0]));
        return out;
    }

    static Integer num(Object o) { return o instanceof Number ? ((Number) o).intValue() : null; }

    @SuppressWarnings("unchecked")
    static Integer rideFor(Map<String, Object> route, String mark) {
        if (route.get("rideMinutesByMark") instanceof Map && !mark.isEmpty()) {
            Integer v = num(((Map<String, Object>) route.get("rideMinutesByMark")).get(mark));
            if (v != null) return v;
        }
        return num(route.get("rideMinutes"));
    }

    @SuppressWarnings("unchecked")
    public static Result nextDepartures(Map<String, Object> route, Map<String, Object> config, Instant nowInstant, int count) {
        if (config == null) config = Collections.emptyMap();
        String sortBy = config.get("sortBy") instanceof String ? (String) config.get("sortBy") : "arrival";
        Integer w = num(route.get("walkMinutes"));
        int walk = w != null ? w : 0;

        ZonedDateTime z = nowInstant.atZone(JST);
        int minutes = z.getHour() * 60 + z.getMinute();
        LocalDate serviceDate = z.toLocalDate();
        if (z.getHour() < SERVICE_DAY_START_HOUR) { minutes += DAY; serviceDate = serviceDate.minusDays(1); }
        long serviceStartMillis = serviceDate.atStartOfDay(JST).toInstant().toEpochMilli();

        int target = minutes + walk;
        int want = sortBy.equals("arrival") ? count + 10 : count;
        List<Departure> list = route.get("segments") instanceof List
            ? journeyCandidates(route, config, serviceDate, target, want)
            : simpleCandidates(route, config, serviceDate, target, want);
        for (Departure d : list) {
            d.departsIn = d.depAbs - minutes;
            d.leaveIn = d.depAbs - minutes - walk;
            d.leaveAtMillis = serviceStartMillis + (long) (d.depAbs - walk) * 60_000L;
        }

        boolean allArrive = !list.isEmpty();
        for (Departure d : list) if (d.arriveAbs == null) { allArrive = false; break; }
        if (sortBy.equals("arrival") && allArrive) {
            // 後から出て同時かそれより早く着く電車がある → 外す
            int best = Integer.MAX_VALUE;
            boolean[] overtaken = new boolean[list.size()];
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).arriveAbs >= best) overtaken[i] = true;
                else best = list.get(i).arriveAbs;
            }
            List<Departure> kept = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) if (!overtaken[i]) kept.add(list.get(i));
            kept.sort((a, b) -> a.arriveAbs.equals(b.arriveAbs) ? Integer.compare(a.depAbs, b.depAbs) : Integer.compare(a.arriveAbs, b.arriveAbs));
            list = kept;
        }

        Result r = new Result();
        r.departures = list.size() > count ? new ArrayList<>(list.subList(0, count)) : list;
        r.dayType = dayType(serviceDate, config);
        r.now = fmt(minutes);
        r.walkMinutes = walk;
        return r;
    }

    @SuppressWarnings("unchecked")
    static List<Departure> simpleCandidates(Map<String, Object> route, Map<String, Object> config, LocalDate serviceDate, int target, int want) {
        Map<String, Object> marks = route.get("marks") instanceof Map ? (Map<String, Object>) route.get("marks") : Collections.emptyMap();
        List<Departure> list = new ArrayList<>();
        for (int k = 0; k < 3 && list.size() < want; k++) {
            String type = dayType(serviceDate.plusDays(k), config);
            for (Object[] dep : parseTable(tableFor(route, type))) {
                int abs = (Integer) dep[0] + k * DAY;
                if (abs < target) continue;
                String mark = (String) dep[1];
                Integer ride = rideFor(route, mark);
                Departure d = new Departure();
                d.time = fmt(abs);
                d.mark = mark;
                Object kind = marks.get(mark);
                d.kind = kind instanceof String ? (String) kind : mark;
                d.arrive = ride != null ? fmt(abs + ride) : null;
                d.depAbs = abs;
                d.arriveAbs = ride != null ? abs + ride : null;
                d.nextServiceDay = k > 0;
                d.dayType = type;
                list.add(d);
                if (list.size() >= want) break;
            }
        }
        return list;
    }

    // ---- 乗り換えありのルート（core.js と同じ計算） ----

    public static final class Leg {
        public String from, to, line, tag, kind, dep, arr;
        public int depAbs, arrAbs;
    }

    static final class Trip {
        final int dep, arr; final String kind;
        Trip(int dep, int arr, String kind) { this.dep = dep; this.arr = arr; this.kind = kind; }
    }

    private static final Pattern TRIP = Pattern.compile("^(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})(?:\\s+(.*))?$", Pattern.DOTALL);
    private static final int MAX_WAIT = 120;

    static int hm(int h, int m) { return (h < SERVICE_DAY_START_HOUR ? h + 24 : h) * 60 + m; }

    /** 1回の計算の中だけで使うキャッシュ（区間 → 曜日区分 → 便） */
    static final class TripCache {
        final java.util.IdentityHashMap<Map<String, Object>, Map<String, List<Trip>>> map = new java.util.IdentityHashMap<>();

        @SuppressWarnings("unchecked")
        List<Trip> get(Map<String, Object> seg, String type) {
            Map<String, List<Trip>> byType = map.get(seg);
            if (byType == null) { byType = new java.util.HashMap<>(); map.put(seg, byType); }
            List<Trip> cached = byType.get(type);
            if (cached != null) return cached;
            Map<String, Object> tt = seg.get("trips") instanceof Map ? (Map<String, Object>) seg.get("trips") : Collections.emptyMap();
            Object w = tt.get("weekday"), s = tt.get("saturday"), h = tt.get("holiday");
            Object src = type.equals("saturday") ? (s != null ? s : (h != null ? h : w)) : type.equals("holiday") ? (h != null ? h : w) : w;
            List<Trip> out = new ArrayList<>();
            if (src instanceof List) {
                for (Object o : (List<Object>) src) {
                    Matcher m = TRIP.matcher(String.valueOf(o));
                    if (!m.matches()) continue;
                    int dep = hm(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                    int arr = hm(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
                    if (arr < dep) arr += DAY;
                    out.add(new Trip(dep, arr, m.group(5) == null ? "" : m.group(5).trim()));
                }
            }
            out.sort((a, b) -> Integer.compare(a.dep, b.dep));
            byType.put(type, out);
            return out;
        }
    }

    static String str(Object o) { return o instanceof String ? (String) o : ""; }

    static Leg makeLeg(Map<String, Object> seg, int dep, int arr, String kind) {
        Leg l = new Leg();
        l.from = str(seg.get("from")); l.to = str(seg.get("to"));
        l.line = str(seg.get("line")); l.tag = str(seg.get("tag")); l.kind = kind;
        l.depAbs = dep; l.arrAbs = arr; l.dep = fmt(dep); l.arr = fmt(arr);
        return l;
    }

    static Trip findTrip(TripCache cache, Map<String, Object> seg, LocalDate serviceDate, int k, int t, Map<String, Object> config) {
        for (Trip tr : cache.get(seg, dayType(serviceDate.plusDays(k), config))) {
            int dep = tr.dep + k * DAY;
            if (dep < t) continue;
            if (dep - t > MAX_WAIT) return null;
            return new Trip(dep, tr.arr + k * DAY, tr.kind);
        }
        return null;
    }

    static final class State {
        final int time; final List<Leg> legs;
        State(int time, List<Leg> legs) { this.time = time; this.legs = legs; }
    }

    @SuppressWarnings("unchecked")
    static State bestJourney(Map<String, Object> route, List<Map<String, Object>> segs, Map<String, Object> config, TripCache cache,
                             LocalDate serviceDate, int k, int depAbs) {
        String origin = str(route.get("origin")), dest = str(route.get("destination"));
        Map<String, Object> transfers = config.get("transferMinutes") instanceof Map ? (Map<String, Object>) config.get("transferMinutes") : Collections.emptyMap();
        Map<String, State> best = new java.util.LinkedHashMap<>();
        String type = dayType(serviceDate.plusDays(k), config);
        for (Map<String, Object> seg : segs) {
            if (!origin.equals(seg.get("from"))) continue;
            for (Trip tr : cache.get(seg, type)) {
                if (tr.dep + k * DAY != depAbs) continue;
                Leg leg = makeLeg(seg, tr.dep + k * DAY, tr.arr + k * DAY, tr.kind);
                String to = str(seg.get("to"));
                State cur = best.get(to);
                if (cur == null || leg.arrAbs < cur.time) {
                    List<Leg> legs = new ArrayList<>(); legs.add(leg);
                    best.put(to, new State(leg.arrAbs, legs));
                }
            }
        }
        for (int round = 0; round < 6; round++) {
            boolean changed = false;
            for (String st : new ArrayList<>(best.keySet())) {
                if (st.equals(dest)) continue;
                State here = best.get(st);
                Integer tw = num(transfers.get(st));
                int wait = tw != null ? tw : 3;
                for (Map<String, Object> seg : segs) {
                    if (!st.equals(seg.get("from")) || origin.equals(seg.get("to"))) continue;
                    Trip tr = findTrip(cache, seg, serviceDate, k, here.time + wait, config);
                    if (tr == null) continue;
                    String to = str(seg.get("to"));
                    State cur = best.get(to);
                    if (cur == null || tr.arr < cur.time) {
                        List<Leg> legs = new ArrayList<>(here.legs);
                        legs.add(makeLeg(seg, tr.dep, tr.arr, tr.kind));
                        best.put(to, new State(tr.arr, legs));
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }
        return best.get(dest);
    }

    @SuppressWarnings("unchecked")
    static List<Departure> journeyCandidates(Map<String, Object> route, Map<String, Object> config, LocalDate serviceDate, int target, int want) {
        Map<String, Object> lib = config.get("segments") instanceof Map ? (Map<String, Object>) config.get("segments") : Collections.emptyMap();
        List<Map<String, Object>> segs = new ArrayList<>();
        for (Object id : (List<Object>) route.get("segments")) {
            Object seg = lib.get(String.valueOf(id));
            if (seg instanceof Map) segs.add((Map<String, Object>) seg);
        }
        String origin = str(route.get("origin"));
        TripCache cache = new TripCache();
        List<Departure> list = new ArrayList<>();
        for (int k = 0; k < 3 && list.size() < want; k++) {
            String type = dayType(serviceDate.plusDays(k), config);
            java.util.TreeSet<Integer> deps = new java.util.TreeSet<>();
            for (Map<String, Object> seg : segs) {
                if (!origin.equals(seg.get("from"))) continue;
                for (Trip tr : cache.get(seg, type)) if (tr.dep + k * DAY >= target) deps.add(tr.dep + k * DAY);
            }
            for (int depAbs : deps) {
                State j = bestJourney(route, segs, config, cache, serviceDate, k, depAbs);
                if (j == null) continue;
                StringBuilder tags = new StringBuilder();
                for (Leg l : j.legs) if (!l.tag.isEmpty()) { if (tags.length() > 0) tags.append("・"); tags.append(l.tag); }
                Departure d = new Departure();
                d.time = fmt(depAbs);
                d.mark = "";
                d.kind = tags.toString();
                d.arrive = fmt(j.time);
                d.depAbs = depAbs;
                d.arriveAbs = j.time;
                d.legs = j.legs;
                d.nextServiceDay = k > 0;
                d.dayType = type;
                list.add(d);
                if (list.size() >= want) break;
            }
        }
        return list;
    }
}
