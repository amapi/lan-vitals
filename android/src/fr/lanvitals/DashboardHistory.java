package fr.lanvitals;

import org.json.JSONArray;
import org.json.JSONObject;

final class DashboardHistory {
    static final int CAPACITY = 60;

    enum Metric {
        CPU, LOAD, RAM_USED, RAM_SHARED, RAM_CACHE, NETWORK_RX, NETWORK_TX,
        DISK_READ, DISK_WRITE, DISK_READ_DELAY, DISK_WRITE_DELAY
    }

    private final double[][] values = new double[Metric.values().length][CAPACITY];
    private int next;
    private int count;

    void clear() {
        next = count = 0;
    }

    int count() { return count; }

    double get(Metric metric, int sample) {
        return values[metric.ordinal()][(next - count + sample + CAPACITY) % CAPACITY];
    }

    double latest(Metric metric) {
        return count == 0 ? Double.NaN : get(metric, count - 1);
    }

    void add(JSONObject sample) {
        put(Metric.CPU, number(sample, "cpu"));
        put(Metric.LOAD, number(sample, "load"));
        double ramTotal = number(sample, "ram_total");
        JSONObject memory = sample.optJSONObject("memory");
        put(Metric.RAM_USED, ratio(number(sample, "ram_used"), ramTotal));
        put(Metric.RAM_SHARED, ratio(number(memory, "Shmem"), ramTotal));
        put(Metric.RAM_CACHE, ratio(number(memory, "Cached"), ramTotal));
        put(Metric.NETWORK_RX, number(sample, "rx"));
        put(Metric.NETWORK_TX, number(sample, "tx"));
        JSONArray disks = sample.optJSONArray("disks");
        addDiskSeries(disks, "read", Metric.DISK_READ, Metric.DISK_READ_DELAY);
        addDiskSeries(disks, "write", Metric.DISK_WRITE, Metric.DISK_WRITE_DELAY);
        next = (next + 1) % CAPACITY;
        count = Math.min(count + 1, CAPACITY);
    }

    private void addDiskSeries(JSONArray disks, String direction, Metric throughput, Metric delay) {
        double bytesPerSecond = 0;
        double operations = 0;
        double weightedDelay = 0;
        boolean reported = false;
        if (disks != null) {
            for (int i = 0; i < disks.length(); i++) {
                JSONObject disk = disks.optJSONObject(i);
                // Les disques entiers évitent de recompter les E/S de leurs partitions.
                // Using whole disks avoids counting their partition I/O twice.
                if (disk == null || !"disk".equals(disk.optString("kind"))) continue;
                double rate = number(disk, direction + "_bps");
                if (!Double.isNaN(rate)) {
                    reported = true;
                    bytesPerSecond += rate;
                }
                double latency = number(disk, direction + "_ms");
                double iops = number(disk, direction + "_iops");
                if (!Double.isNaN(latency) && iops > 0) {
                    weightedDelay += latency * iops;
                    operations += iops;
                }
            }
        }
        put(throughput, reported ? bytesPerSecond : Double.NaN);
        put(delay, operations > 0 ? weightedDelay / operations : Double.NaN);
    }

    private static double ratio(double value, double total) {
        return total > 0 ? Math.min(100, 100 * value / total) : Double.NaN;
    }

    private static double number(JSONObject data, String key) {
        double value = data == null ? Double.NaN : data.optDouble(key, Double.NaN);
        return value >= 0 && value < Double.POSITIVE_INFINITY ? value : Double.NaN;
    }

    private void put(Metric metric, double value) {
        values[metric.ordinal()][next] = value;
    }
}
