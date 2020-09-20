/*
 * Copyright (c) 2020 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.fs2.io

import cats.effect.{Timer, Sync, Resource}

import fs2.Stream

import com.codahale.metrics.{Slf4jReporter, Gauge, MetricRegistry}

import org.slf4j.LoggerFactory

import com.snowplowanalytics.snowplow.enrich.fs2.Environment

trait Metrics[F[_]] {
  /** Send latest metrics to reporter */
  def report: F[Unit]
  /**
   * Track latency between collector hit and enrichment
   * This function gets current timestamp by itself
   */
  def enrichLatency(collectorTstamp: Option[Long]): F[Unit]
  /** Increment raw payload count */
  def rawCount: F[Unit]
  /** Increment good enriched events */
  def goodCount: F[Unit]
  /** Increment bad events */
  def badCount: F[Unit]
}

object Metrics {

  val LoggerName = "enrich.metrics"
  val LatencyGaugeName = "enrich.metrics.latency"
  val RawCounterName = "enrich.metrics.raw.count"
  val GoodCounterName = "enrich.metrics.good.count"
  val BadCounterName = "enrich.metrics.bad.count"

  def run[F[_] : Sync : Timer](env: Environment[F]) =
    env.config.metricsReportPeriod match {
      case Some(period) =>
        Stream.awakeEvery[F](period).evalMap(_ => env.metrics.report)
      case None =>
        Stream.empty.covary[F]
    }

  /**
   * Technically `Resource` doesn't give us much as we don't allocate a thread pool,
   * but it will make sure the last report is issued
   */
  def resource[F[_] : Sync]: Resource[F, Metrics[F]] =
    Resource
      .make(initialise) { case (res, _) => Sync[F].delay(res.close()) }
      .map { case (res, reg) => mkMetrics[F](res, reg) }

  def initialise[F[_] : Sync] =
    Sync[F].delay {
      val registry = new MetricRegistry()
      val logger = LoggerFactory.getLogger(LoggerName)
      val reporter = Slf4jReporter.forRegistry(registry).outputTo(logger).build()
      (reporter, registry)
    }

  def mkMetrics[F[_] : Sync](reporter: Slf4jReporter, registry: MetricRegistry): Metrics[F] =
    new Metrics[F] {
      val rawCounter = registry.counter(RawCounterName)
      val goodCounter = registry.counter(GoodCounterName)
      val badCounter = registry.counter(BadCounterName)

      def report: F[Unit] =
        Sync[F].delay(reporter.report())

      def enrichLatency(collectorTstamp: Option[Long]): F[Unit] = {
        collectorTstamp match {
          case Some(delay) =>
            Sync[F].delay {
              registry.remove(LatencyGaugeName)
              val _ = registry.register(LatencyGaugeName, new Gauge[Long] {
                def getValue: Long = System.currentTimeMillis() - delay
              })
            }
          case None =>
            Sync[F].unit
        }
      }

      def rawCount: F[Unit] =
        Sync[F].delay(rawCounter.inc())

      def goodCount: F[Unit] =
        Sync[F].delay(goodCounter.inc())

      def badCount: F[Unit] =
        Sync[F].delay(badCounter.inc())
    }
}

