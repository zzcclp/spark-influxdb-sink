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

import java.net.ConnectException;
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

    private final String profilerName = "DAGScheduler";

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
            Map<String, Map<String, Gauge>> groupedGauges = groupGauges(gauges);
            for (Map.Entry<String, Map<String, Gauge>> entry : groupedGauges.entrySet()) {
                reportGaugeGroup(entry.getKey(), entry.getValue(), now);
            }
            this.influxDB.flush();

            // TODO : support to reporter these types of data
            /* for (Map.Entry<String, Counter> entry : counters.entrySet()) {
                reportCounter(timestamp, entry.getKey(), entry.getValue());
            }

            for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
                reportHistogram(timestamp, entry.getKey(), entry.getValue());
            }

            for (Map.Entry<String, Meter> entry : meters.entrySet()) {
                reportMeter(timestamp, entry.getKey(), entry.getValue());
            }

            for (Map.Entry<String, Timer> entry : timers.entrySet()) {
                reportTimer(timestamp, entry.getKey(), entry.getValue());
            } */
        } catch (Exception e) {
            LOGGER.warn("Unable to report to InfluxDB with error '{}'. Discarding data.", e.getMessage());
        }
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

    private void reportGaugeGroup(String name, Map<String, Gauge> gaugeGroup, long now) {
        Map<String, Object> fields = new HashMap<String, Object>();
        for (Map.Entry<String, Gauge> entry : gaugeGroup.entrySet()) {
            Object gaugeValue = sanitizeGauge(entry.getValue().getValue());
            if (gaugeValue != null) {
                fields.put(entry.getKey(), gaugeValue);
            }
        }

        // Point
        Point point = Point.measurement(profilerName)
                .time(now, TimeUnit.MILLISECONDS)
                .fields(fields)
                .tag("metricsTag", name)
                .build();
        // BatchPoints
        BatchPoints batchPoints = BatchPoints.database(this.dataBase)
                .consistency(InfluxDB.ConsistencyLevel.ALL)
                .retentionPolicy("autogen")
                .build();
        batchPoints.point(point);
        // Write
        this.influxDB.write(batchPoints);
    }

    protected String sanitize(String name) {
        return name;
    }
}
