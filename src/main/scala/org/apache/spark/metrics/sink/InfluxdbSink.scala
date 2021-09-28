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

package org.apache.spark.metrics.sink

import java.util.{Locale, Properties}
import java.util.concurrent.TimeUnit
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.influxdb.InfluxDbReporter
import okhttp3.OkHttpClient
import org.influxdb.{InfluxDB, InfluxDBFactory}
import org.apache.spark.SecurityManager
import org.apache.spark.metrics.MetricsSystem


class InfluxdbSink(val property: Properties, val registry: MetricRegistry,
                   securityMgr: SecurityManager) extends Sink {
  val INFLUXDB_KEY_PERIOD = "period"
  val INFLUXDB_DEFAULT_PERIOD = 10

  val INFLUXDB_KEY_UNIT = "unit"
  val INFLUXDB_DEFAULT_UNIT: TimeUnit = TimeUnit.SECONDS

  val INFLUXDB_KEY_HOST = "host"
  val INFLUXDB_KEY_PORT = "port"
  val INFLUXDB_KEY_DATABASE = "database"
  val INFLUXDB_KEY_USERNAME = "username"
  val INFLUXDB_KEY_PASSWORD = "password"

  def propertyToOption(prop: String): Option[String] = Option(property.getProperty(prop))

  if (!propertyToOption(INFLUXDB_KEY_HOST).isDefined) {
    throw new Exception("Influxdb sink requires 'host' property.")
  }

  if (!propertyToOption(INFLUXDB_KEY_PORT).isDefined) {
    throw new Exception("Influxdb sink requires 'port' property.")
  }

  val host = propertyToOption(INFLUXDB_KEY_HOST).get
  val port = propertyToOption(INFLUXDB_KEY_PORT).get.toInt
  val dataBase = propertyToOption(INFLUXDB_KEY_DATABASE).getOrElse("metrics")
  val userName = propertyToOption(INFLUXDB_KEY_USERNAME).getOrElse("admin")
  val password = propertyToOption(INFLUXDB_KEY_PASSWORD).getOrElse("admin")
  val pollPeriod = propertyToOption(INFLUXDB_KEY_PERIOD).map(_.toInt)
    .getOrElse(INFLUXDB_DEFAULT_PERIOD)
  val pollUnit: TimeUnit = propertyToOption(INFLUXDB_KEY_UNIT)
    .map(u => TimeUnit.valueOf(u.toUpperCase(Locale.ROOT)))
    .getOrElse(INFLUXDB_DEFAULT_UNIT)

  MetricsSystem.checkMinimalPollingPeriod(pollUnit, pollPeriod)

  val url: String = "http://" + host + ":" + port
  val client = (new OkHttpClient.Builder())
    .connectTimeout(1, TimeUnit.MINUTES)
    .readTimeout(1, TimeUnit.MINUTES)
    .writeTimeout(1, TimeUnit.MINUTES)
    .retryOnConnectionFailure(true)
  val influxDB = InfluxDBFactory.connect(url, userName, password, client)
    .enableBatch(100, 1000, TimeUnit.MILLISECONDS)
    .enableGzip()
    .setLogLevel(InfluxDB.LogLevel.NONE)
  val reporter: InfluxDbReporter = InfluxDbReporter.forRegistry(registry)
      .convertDurationsTo(TimeUnit.MILLISECONDS)
      .convertRatesTo(TimeUnit.SECONDS)
      .build(influxDB, dataBase)

  override def start(): Unit = {
    reporter.start(pollPeriod, pollUnit)
  }

  override def stop(): Unit = {
    reporter.stop()
  }

  override def report(): Unit = {
    reporter.report()
  }
}

