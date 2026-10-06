import com.traincheck.widget.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

// Java版ロジックの出力を1行ずつ出す（JS版と diff する）
public class CrossCheck {
    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        Map<String, Object> cfg = (Map<String, Object>) MiniJson.parse(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"));
        List<Object> routes = (List<Object>) cfg.get("routes");
        for (String line : Files.readAllLines(Paths.get(a[1]))) {
            long t = Long.parseLong(line.trim());
            for (Object ro : routes) {
                Map<String, Object> r = (Map<String, Object>) ro;
                TrainLogic.Result res = TrainLogic.nextDepartures(r, cfg, Instant.ofEpochMilli(t), 4);
                StringBuilder b = new StringBuilder(t + " " + r.get("id") + " " + res.dayType + " " + res.now + " |");
                for (TrainLogic.Departure d : res.departures) {
                    b.append(" ").append(d.time).append(">").append(d.arrive).append(" ").append(d.kind).append(" ").append(d.leaveIn).append(" ").append(d.nextServiceDay)
                     .append(" ").append(d.leaveAtMillis);
                    if (d.legs != null) for (TrainLogic.Leg l : d.legs) b.append(" {").append(l.from).append(l.dep).append(">").append(l.to).append(l.arr).append(l.kind).append("}");
                }
                System.out.println(b);
            }
        }
    }
}
