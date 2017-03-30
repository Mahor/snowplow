/*
 * Copyright (c) 2017 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.rdbloader

// File
import java.io.File

import com.snowplowanalytics.rdbloader.Targets.{PostgresqlConfig, RedshiftConfig}

// cats
import cats.syntax.cartesian._
import cats.data.{ ValidatedNel, NonEmptyList }
import cats.syntax.validated._
import cats.syntax.either._

// Iglu
import com.snowplowanalytics.iglu.client.Resolver

// This project
import Compat._
import Utils._
import generated.ProjectMetadata
import Targets.StorageTarget

object Main {

  import scopt.Read

  implicit val optionalStepRead =
    Read.reads { (Utils.fromString[OptionalWorkStep](_)).andThen(_.right.get) }

  implicit val skippableStepRead =
    Read.reads { (Utils.fromString[SkippableStep](_)).andThen(_.right.get) }

  sealed trait OptionalWorkStep extends StringEnum
  case object Compupdate extends OptionalWorkStep { def asString = "compupdate" }
  case object Vacuum extends OptionalWorkStep { def asString = "vacuum" }

  sealed trait SkippableStep extends StringEnum
  case object ArchiveEnriched extends SkippableStep { def asString = "archive_enriched" }
  case object Download extends SkippableStep { def asString = "download" }
  case object Analyze extends SkippableStep { def asString = "analyze" }
  case object Delete extends SkippableStep { def asString = "delete" }
  case object Shred extends SkippableStep { def asString = "shred" }
  case object Load extends SkippableStep { def asString = "load" }

  // TODO: this probably should contain base64-encoded strings instead of `File`
  case class CliConfig(
    config: File,
    targetsDir: File,
    resolver: File,
    include: Seq[OptionalWorkStep],
    skip: Seq[SkippableStep],
    b64config: Boolean)

  private val rawCliConfig = CliConfig(new File("config.yml"), new File("targets"), new File("resolver.json"), Nil, Nil, true)

  case class AppConfig(
    configYaml: Config,
    b64config: Boolean,     // TODO: Why is it in AppConfig
    targets: Set[Targets.StorageTarget],
    include: List[OptionalWorkStep],
    skip: List[SkippableStep]) // Contains parsed configs

  def loadResolver(resolverConfig: File): ValidatedNel[ConfigError, Resolver] = {
    if (!resolverConfig.isFile) ParseError(s"[${resolverConfig.getAbsolutePath}] is not a file").invalidNel
    else if (!resolverConfig.canRead) ParseError(s"Resolver config [${resolverConfig.getAbsolutePath} is not readable").invalidNel
    else {
      val json = readFile(resolverConfig).flatMap(Utils.safeParse).toValidatedNel
      json.andThen(convertIgluResolver)
    }
  }

  def transform(cliConfig: CliConfig): ValidatedNel[ConfigError, AppConfig] = {
    val resolver = loadResolver(cliConfig.resolver)
    val targets: ValidatedNel[ConfigError, List[StorageTarget]] = resolver.andThen { Targets.loadTargetsFromDir(cliConfig.targetsDir, _) }
    val config: ValidatedNel[ConfigError, Config] = Config.loadFromFile(cliConfig.config).toValidatedNel

    (targets |@| config).map {
      case (t, c) => AppConfig(c, cliConfig.b64config, t.toSet, cliConfig.include.toList, cliConfig.skip.toList)
    }
  }

  def getErrorMessage(errors: NonEmptyList[ConfigError]): String = {
    s"""Following ${errors.toList.length} encountered:
       |${errors.map(_.message.padTo(3, ' ')).toList.mkString("\n")}""".stripMargin
  }

  val parser = new scopt.OptionParser[CliConfig]("scopt") {
    head("Relational Database Loader", ProjectMetadata.version)

    opt[File]('c', "config").required().valueName("<file>").
      action((x, c) ⇒ c.copy(config = x)).
      text("configuration file")

    opt[File]('t', "targets").required().valueName("<dir>").
      action((x, c) => c.copy(targetsDir = x)).
      text("directory with storage targets configuration JSONs")

    opt[File]('r', "resolver").required().valueName("<dir>").
      action((x, c) => c.copy(resolver = x)).
      text("Self-describing JSON for Iglu resolver")

    opt[Unit]('b', "base64-config-string").action((_, c) ⇒
      c.copy(b64config = true)).text("base64-encoded configuration string")

    opt[Seq[OptionalWorkStep]]('i', "include").action((x, c) ⇒
      c.copy(include = x)).text("include optional work steps")

    opt[Seq[SkippableStep]]('s', "skip").action((x, c) =>
      c.copy(skip = x)).text("skip steps")

    help("help").text("prints this usage text")

  }

  def processTargets(config: AppConfig, target: StorageTarget) = target match {
    case postgresqlTarget: PostgresqlConfig =>
      val downloadFolder = config.configYaml.storage.download.folder.getOrElse("") // TODO: get default dir
      val monitoring = config.configYaml.monitoring.snowplow == null               // TODO: handle monitoring
      loaders.PostgresqlLoader.loadEvents(downloadFolder, postgresqlTarget, config.include.toSet, config.skip.toSet, monitoring)
    case redshiftTarget: RedshiftConfig =>
      ???
    case _ =>
      ??? // Is not supported by RDB Loader
  }

  def run(config: AppConfig) = {
    config.targets

  }

  def main(argv: Array[String]): Unit = {
    val value = parser.parse(argv, rawCliConfig) match {
      case Some(config) => transform(config).leftMap(getErrorMessage)
      case None => sys.exit(1)
    }

    println(value)
  }
}
