/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.codahale.metrics.influxdb;

import com.codahale.metrics.Clock;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import org.influxdb.InfluxDB;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

public class InfluxDbReporter extends ScheduledReporter {

    public static InfluxDbReporter.Builder forRegistry(MetricRegistry registry) {
        return new InfluxDbReporter.Builder(registry);
    }

    public static class Builder {
        private final MetricRegistry registry;
        private Locale locale;
        private TimeUnit rateUnit;
        private TimeUnit durationUnit;
        private Clock clock;
        private MetricFilter filter;

        private Builder(MetricRegistry registry) {
            this.registry = registry;
            this.locale = Locale.getDefault();
            this.rateUnit = TimeUnit.SECONDS;
            this.durationUnit = TimeUnit.MILLISECONDS;
            this.clock = Clock.defaultClock();
            this.filter = MetricFilter.ALL;
        }

        public InfluxDbReporter.Builder formatFor(Locale locale) {
            this.locale = locale;
            return this;
        }

        public InfluxDbReporter.Builder convertRatesTo(TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        public InfluxDbReporter.Builder convertDurationsTo(TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        public InfluxDbReporter.Builder withClock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public InfluxDbReporter.Builder filter(MetricFilter filter) {
            this.filter = filter;
            return this;
        }

        public InfluxDbReporter build(InfluxDB influxDB, String dataBase) {
            return new InfluxDbReporter(registry,
                    influxDB,
                    dataBase,
                    locale,
                    rateUnit,
                    durationUnit,
                    clock,
                    filter);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(InfluxDbReporter.class);
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final Locale locale;
    private final Clock clock;
    private final InfluxDB influxDB;
    private final String dataBase;

    private final String profilerNamePrefix = "SparkMetrics";

    private InfluxDbReporter(MetricRegistry registry,
                             InfluxDB influxDB,
                             String dataBase,
                             Locale locale,
                             TimeUnit rateUnit,
                             TimeUnit durationUnit,
                             Clock clock,
                             MetricFilter filter) {
        super(registry, "influxdb-reporter", filter, rateUnit, durationUnit);
        this.influxDB = influxDB;
        this.dataBase = dataBase;
        this.locale = locale;
        this.clock = clock;
    }

    @Override
    public void report(SortedMap<String, Gauge> gauges, SortedMap<String, Counter> counters,
                       SortedMap<String, Histogram> histograms, SortedMap<String, Meter> meters,
                       SortedMap<String, Timer> timers) {
        // final long timestamp = TimeUnit.MILLISECONDS.toSeconds(clock.getTime());
        final long now = System.currentTimeMillis();

        try {
            // BatchPoints
            BatchPoints batchPoints = BatchPoints.database(this.dataBase)
                    .consistency(InfluxDB.ConsistencyLevel.ONE)
                    .retentionPolicy("autogen")
                    .build();

            /* Map<String, Map<String, Gauge>> groupedGauges = groupGauges(gauges);
            for (Map.Entry<String, Map<String, Gauge>> entry : groupedGauges.entrySet()) {
                reportGaugeGroup(entry.getKey(), entry.getValue(), now, batchPoints);
            } */
            for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
                reportGauge(entry.getKey(), entry.getValue(), now, batchPoints);
            }

            for (Map.Entry<String, Counter> entry : counters.entrySet()) {
                reportCounter(entry.getKey(), entry.getValue(), now, batchPoints);
            }

            for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
                reportHistogram(entry.getKey(), entry.getValue(), now, batchPoints);
            }

            for (Map.Entry<String, Meter> entry : meters.entrySet()) {
                reportMeter(entry.getKey(), entry.getValue(), now, batchPoints);
            }

            for (Map.Entry<String, Timer> entry : timers.entrySet()) {
                reportTimer(entry.getKey(), entry.getValue(), now, batchPoints);
            }

            // Write
            this.influxDB.write(batchPoints);
        } catch (Exception e) {
            LOGGER.warn("Unable to report to InfluxDB with error '{}'. Discarding data.", e.getMessage());
        }
    }

    private void reportGauge(String name, Gauge gauge, long now, BatchPoints batchPoints) {
        Map<String, Object> fields = new HashMap<String, Object>();
        Object gaugeValue = sanitizeGauge(gauge.getValue());
        fields.put("value", gaugeValue);

        // Point
        Point point = Point.measurement(profilerNamePrefix + "-Gauge")
                .time(now, TimeUnit.MILLISECONDS)
                .fields(fields)
                .tag("metricsTag", name)
                .build();

        batchPoints.point(point);
    }

    private void reportCounter(String name, Counter counter, long now, BatchPoints batchPoints) {
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("count", counter.getCount());

        // Point
        Point point = Point.measurement(profilerNamePrefix + "-Counter")
                .time(now, TimeUnit.MILLISECONDS)
                .fields(fields)
                .tag("metricsTag", name)
                .build();

        batchPoints.point(point);
    }

    private void reportHistogram(String name, Histogram histogram,
                                 long now, BatchPoints batchPoints) {
        final Snapshot snapshot = histogram.getSnapshot();
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("count", histogram.getCount());
        fields.put("min", snapshot.getMin());
        fields.put("max", snapshot.getMax());
        fields.put("mean", snapshot.getMean());
        fields.put("stddev", snapshot.getStdDev());
        fields.put("p50", snapshot.getMedian());
        fields.put("p75", snapshot.get75thPercentile());
        fields.put("p95", snapshot.get95thPercentile());
        fields.put("p98", snapshot.get98thPercentile());
        fields.put("p99", snapshot.get99thPercentile());
        fields.put("p999", snapshot.get999thPercentile());

        // Point
        Point point = Point.measurement(profilerNamePrefix + "-Histogram")
                .time(now, TimeUnit.MILLISECONDS)
                .fields(fields)
                .tag("metricsTag", name)
                .build();

        batchPoints.point(point);
    }

    private void reportMeter(String name, Meter meter, long now, BatchPoints batchPoints) {
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("count", meter.getCount());
        fields.put("mean_rate", convertRate(meter.getMeanRate()));
        fields.put("m1_rate", convertRate(meter.getOneMinuteRate()));
        fields.put("m5_rate", convertRate(meter.getFiveMinuteRate()));
        fields.put("m15_rate", convertRate(meter.getFifteenMinuteRate()));
        fields.put("rate_unit", "events/" + getRateUnit());

        // Point
        Point point = Point.measurement(profilerNamePrefix + "-Meter")
                .time(now, TimeUnit.MILLISECONDS)
                .fields(fields)
                .tag("metricsTag", name)
                .build();

        batchPoints.point(point);
    }

    private void reportTimer(String name, Timer timer, long now, BatchPoints batchPoints) {
        final Snapshot snapshot = timer.getSnapshot();
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("count", timer.getCount());
        fields.put("min", convertDuration(snapshot.getMin()));
        fields.put("max", convertDuration(snapshot.getMax()));
        fields.put("mean", convertDuration(snapshot.getMean()));
        fields.put("stddev", convertDuration(snapshot.getStdDev()));
        fields.put("p50", convertDuration(snapshot.getMedian()));
        fields.put("p75", convertDuration(snapshot.get75thPercentile()));
        fields.put("p95", convertDuration(snapshot.get95thPercentile()));
        fields.put("p98", convertDuration(snapshot.get98thPercentile()));
        fields.put("p99", convertDuration(snapshot.get99thPercentile()));
        fields.put("p999", convertDuration(snapshot.get999thPercentile()));
        fields.put("mean_rate", convertRate(timer.getMeanRate()));
        fields.put("m1_rate", convertRate(timer.getOneMinuteRate()));
        fields.put("m5_rate", convertRate(timer.getFiveMinuteRate()));
        fields.put("m15_rate", convertRate(timer.getFifteenMinuteRate()));
        fields.put("rate_unit", "calls/" + getRateUnit());
        fields.put("duration_unit", getDurationUnit());

        // Point
        Point point = Point.measurement(profilerNamePrefix + "-Timer")
                .time(now, TimeUnit.MILLISECONDS)
                .fields(fields)
                .tag("metricsTag", name)
                .build();

        batchPoints.point(point);
    }

    private Map<String, Map<String, Gauge>> groupGauges(SortedMap<String, Gauge> gauges) {
        Map<String, Map<String, Gauge>> groupedGauges = new HashMap<>();
        for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
            final String metricName;
            String fieldName;
            int lastDotIndex = entry.getKey().lastIndexOf(".");
            if (lastDotIndex != -1) {
                metricName = entry.getKey().substring(0, lastDotIndex);
                fieldName = entry.getKey().substring(lastDotIndex + 1);
                // avoid influxdb-keyword field like time or tag, see https://github.com/influxdata/influxdb/issues/1834
                if (fieldName.equalsIgnoreCase("time"))
                    fieldName = "time_";
            } else {
                // no `.` to group by in the metric name, just report the metric as is
                metricName = entry.getKey();
                fieldName = "value";
            }
            Map<String, Gauge> fields = groupedGauges.get(metricName);

            if (fields == null) {
                fields = new HashMap<String, Gauge>();
            }
            fields.put(fieldName, entry.getValue());
            groupedGauges.put(metricName, fields);
        }
        return groupedGauges;
    }

    private Object sanitizeGauge(Object value) {
        final Object finalValue;
        if (value instanceof Double && (Double.isInfinite((Double) value) || Double.isNaN((Double) value))) {
            finalValue = null;
        } else if (value instanceof Float && (Float.isInfinite((Float) value) || Float.isNaN((Float) value))) {
            finalValue = null;
        } else {
            finalValue = value;
        }
        return finalValue;
    }

    private void reportGaugeGroup(String name, Map<String, Gauge> gaugeGroup, long now,
                                  BatchPoints batchPoints) {
        Map<String, Object> fields = new HashMap<String, Object>();
        for (Map.Entry<String, Gauge> entry : gaugeGroup.entrySet()) {
            Object gaugeValue = sanitizeGauge(entry.getValue().getValue());
            if (gaugeValue != null) {
                fields.put(entry.getKey(), gaugeValue);
            }
        }

        // Point
        Point point = Point.measurement(profilerNamePrefix)
                .time(now, TimeUnit.MILLISECONDS)
                .fields(fields)
                .tag("metricsTag", name)
                .build();

        batchPoints.point(point);
    }

    protected String sanitize(String name) {
        return name;
    }
}
