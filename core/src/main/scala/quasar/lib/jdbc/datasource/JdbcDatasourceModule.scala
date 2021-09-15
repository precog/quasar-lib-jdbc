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

import quasar.lib.jdbc.TransactorConfig

import java.lang.String

import scala.util.Either

import argonaut._

import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._

import quasar.api.datasource.{DatasourceError => DE}
import quasar.connector.MonadResourceErr

abstract class JdbcDatasourceModule[C: DecodeJson] extends DynamicConfigJdbcDatasourceModule[C] {

  def transactorConfig(config: C): Either[NonEmptyList[String], TransactorConfig]

  def configResource[F[_]: ConcurrentEffect: ContextShift: MonadResourceErr: Timer](
      config: Json,
      cfg: C)
      : Resource[F, Either[InitError, TransactorConfig]] =
    transactorConfig(cfg)
      .leftMap(errs => scalaz.NonEmptyList(errs.head, errs.tail: _*))
      .leftMap(DE.invalidConfiguration[Json, InitError](kind, sanitizeConfig(config), _))
      .pure[Resource[F, *]]
}
