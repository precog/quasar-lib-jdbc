/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.lib.jdbc.datasource

import quasar.lib.jdbc.{ManagedTransactor, Redacted, TransactorConfig}

import java.lang.Exception
import java.util.UUID

import scala.{StringContext, Option}
import scala.concurrent.ExecutionContext
import scala.util.{Either, Left, Random, Right}

import argonaut._, Argonaut._, ArgonautCats._

import cats.Hash
import cats.data.EitherT
import cats.effect._
import cats.implicits._

import doobie._

import quasar.RateLimiting
import quasar.api.datasource.{DatasourceError => DE}
import quasar.connector.{ByteStore, MonadResourceErr, ExternalCredentials}
import quasar.connector.datasource.DatasourceModule

import org.slf4s.{Logger, LoggerFactory}

/** A Quasar DatasourceModule for JDBC sources.
  *
  * Handles boilerplate common to all JDBC datasources, such as
  *   - parsing JSON into a vendor-specific config
  *   - constructing a pooled `Transactor` along with the necessary threadpools
  *   - validating a connection to the database can be established
  *   - logging
  */
abstract class DynamicConfigJdbcDatasourceModule[C: DecodeJson] extends DatasourceModule {

  type InitError = DE.InitializationError[Json]

  /** Returns the transactor configuration resource to use for the datasource having
    * the specified configuration or a list of errors describing why a
    * transactor could not be configured.
    */
  def configResource[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](
      cfg: Json,
      config: C)
      : Resource[F, Either[InitError, TransactorConfig]]

  def jdbcDatasource[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer, A](
      config: C,
      transactor: Transactor[F],
      rateLimiter: RateLimiting[F, A],
      byteStore: ByteStore[F],
      getAuth: UUID => F[Option[ExternalCredentials[F]]],
      log: Logger)
      : Resource[F, Either[InitError, DatasourceModule.DS[F]]]

  ////

  def datasource[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer, A: Hash](
      config: Json,
      rateLimiter: RateLimiting[F, A],
      byteStore: ByteStore[F],
      getAuth: UUID => F[Option[ExternalCredentials[F]]])(
      implicit ec: ExecutionContext)
      : Resource[F, Either[InitError, DatasourceModule.DS[F]]] = {

    val id = s"${kind.name.value}-v${kind.version}"

    val cfg0: Either[InitError, C] =
      config.as[C].fold(
        (_, c) =>
          Left(DE.malformedConfiguration[Json, InitError](
            kind,
            jString(Redacted),
            s"Failed to decode $id JSON at ${c.toList.map(_.show).mkString(", ")}")),
        Right(_))

    def liftF[X](fa: F[X]): EitherT[Resource[F, ?], InitError, X] =
      EitherT.right(Resource.eval(fa))

    val init = for {
      cfg <- EitherT.fromEither[Resource[F, ?]](cfg0)

      xaCfg <- EitherT(configResource(config, cfg))

      tag <- liftF(Sync[F].delay(Random.alphanumeric.take(6).mkString))

      debugId = s"datasource.$id.$tag"

      xa <- EitherT {
        ManagedTransactor[F](debugId, xaCfg)
          .attemptNarrow[Exception]
          .map(_.leftMap(DE.connectionFailed[Json, InitError](kind, sanitizeConfig(config), _)))
      }

      slog <- liftF(Sync[F].delay(LoggerFactory(s"quasar.lib.$debugId")))

      ds <- EitherT(jdbcDatasource(cfg, xa, rateLimiter, byteStore, getAuth, slog))

      _ <- liftF(Sync[F].delay(slog.info(s"Initialized $debugId: ${sanitizeConfig(config)}")))
    } yield ds

    init.value
  }
}
