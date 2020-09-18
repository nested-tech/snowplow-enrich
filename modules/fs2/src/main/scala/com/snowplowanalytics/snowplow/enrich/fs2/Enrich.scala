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
package com.snowplowanalytics.snowplow.enrich.fs2

import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

import org.joda.time.DateTime

import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits._

import cats.effect.{Clock, Concurrent, ContextShift, Sync}

import fs2.Stream

import _root_.io.sentry.SentryClient

import _root_.io.circe.Json
import _root_.io.circe.syntax._

import _root_.io.chrisdavenport.log4cats.Logger
import _root_.io.chrisdavenport.log4cats.slf4j.Slf4jLogger

import com.snowplowanalytics.iglu.client.Client

import com.snowplowanalytics.snowplow.badrows.{Processor, BadRow, Failure, Payload => BadRowPayload}

import com.snowplowanalytics.snowplow.enrich.common.EtlPipeline
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.adapters.AdapterRegistry
import com.snowplowanalytics.snowplow.enrich.common.loaders.{CollectorPayload, ThriftLoader}
import com.snowplowanalytics.snowplow.enrich.common.enrichments.EnrichmentRegistry

object Enrich {

  /** Default adapter registry, can be constructed dynamically in future */
  val adapterRegistry = new AdapterRegistry()

  val processor: Processor = Processor(generated.BuildInfo.name, generated.BuildInfo.version)

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  /**
   * Run a primary enrichment stream, reading from [[Environment]] source, enriching
   * via [[enrichWith]] and sinking into [[GoodSink]] and [[BadSink]] respectively.
   * Can be stopped via _stop signal_ from [[Environment]]
   */
  def run[F[_]: Concurrent: ContextShift: Clock](env: Environment[F]): Stream[F, Unit] = {
    val enrich: Enrich[F] = enrichWith[F](env.enrichments.registry, env.resolver, env.sentry)
    env.source
      .pauseWhen(env.stop)
      .parEvalMapUnordered(16)(payload => env.blocker.blockOn(enrich(payload)))
      .flatMap(_.decompose[BadRow, EnrichedEvent])
      .observeEither(env.bad, env.good)
      .void
  }

  /** Enrich a single [[CollectorPayload]] to get list of bad rows and/or enriched events */
  def enrichWith[F[_]: Clock: Sync](
    enrichRegistry: EnrichmentRegistry[F],
    igluClient: Client[F, Json],
    sentry: Option[SentryClient]
  )(
    row: Payload[F, Array[Byte]]
  ): F[Result[F]] = {
    val payload = ThriftLoader.toCollectorPayload(row.data, processor)
    val result = Logger[F].debug(payloadToString(payload)) *>
      Clock[F]
        .realTime(TimeUnit.MILLISECONDS)
        .map(millis => new DateTime(millis))
        .flatMap { now =>
          EtlPipeline.processEvents[F](adapterRegistry, enrichRegistry, igluClient, processor, now, payload)
        }
        .map(enriched => Payload(enriched, row.ack))

    result.handleErrorWith(sendToSentry[F](row, sentry))
  }

  /** Stringify `ThriftLoader` result for debugging purposes */
  def payloadToString(payload: ValidatedNel[BadRow.CPFormatViolation, Option[CollectorPayload]]): String =
    payload.fold(_.asJson.noSpaces, _.map(_.toBadRowPayload.asJson.noSpaces).getOrElse("None"))

  /** Transform enriched event into canonical TSV */
  def encodeEvent(enrichedEvent: EnrichedEvent): String =
    enrichedEvent.getClass.getDeclaredFields
      .filterNot(_.getName.equals("pii"))
      .map { field =>
        field.setAccessible(true)
        Option(field.get(enrichedEvent)).getOrElse("")
      }
      .mkString("\t")

  /** Log an error, turn the problematic [[CollectorPayload]] into [[BadRow]] and notify Sentry if configured */
  def sendToSentry[F[_]: Sync: Clock](original: Payload[F, Array[Byte]], sentry: Option[SentryClient])(error: Throwable): F[Result[F]] =
    for {
      _ <- Logger[F].error("Runtime exception during payload enrichment. CollectorPayload converted to generic_error and ack'ed")
      now <- Clock[F].realTime(TimeUnit.MILLISECONDS).map(Instant.ofEpochMilli)
      _ <- original.ack
      badRow = genericBadRow(original.data, now, error)
      _ <- sentry match {
             case Some(client) =>
               Sync[F].delay(client.sendException(error))
             case None =>
               Sync[F].unit
           }
    } yield Payload(List(badRow.invalid), Sync[F].unit)

  /** Build a `generic_error` bad row for unhandled runtime errors */
  def genericBadRow(
    row: Array[Byte],
    time: Instant,
    error: Throwable
  ): BadRow = {
    val base64 = new String(Base64.getEncoder.encode(row))
    val rawPayload = BadRowPayload.RawPayload(base64)
    val failure = Failure.GenericFailure(time, NonEmptyList.one(error.toString))
    BadRow.GenericError(processor, failure, rawPayload)
  }
}