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

import cats.syntax.either._

import io.circe._
import io.circe.Decoder._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.decoding.ConfiguredDecoder

import ConfigParser.{ ParseError, JsonHash }

/**
 * Full Snowplow `config.yml` runtime representation
 */
case class Config(
  aws: Config.SnowplowAws,
  collectors: Config.Collectors,
  enrich: Config.Enrich,
  storage: Config.Storage,
  monitoring: Config.Storage)

object Config {

  // aws section

  case class SnowplowAws(
    accessKeyId: String,
    secretAccessKey: String,
    s3: SnowplowS3,
    emr: SnowplowEmr)

  // aws.s3 section

  case class SnowplowS3(
    region: String,
    buckets: SnowplowBuckets)

  case class SnowplowBuckets(
    assets: String,
    jsonpathAssets: Option[String],
    log: String,
    enriched: EnrichedBucket,
    shredded: ShreddedBucket)

  case class RawBucket(
    in: List[String],
    processing: String,
    archive: String)

  case class EnrichedBucket(
    good: String,
    bad: String,
    errors: String,
    archive: String)

  case class ShreddedBucket(
    good: String,
    bad: String,
    errors: String,
    archive: String)

  // aws.emr section

  case class SnowplowEmr(
    amiVersion: String,
    region: String,
    jobflowRole: String,
    serviceRole: String,
    placement: Option[String],
    ec2SubnetId: Option[String],
    ec2KeyName: String,
    bootstrap: List[String],
    software: EmrSoftware,
    jobflow: EmrJobflow,
    bootstrapFailureTries: Int,
    additionalInfo: Option[String])   // TODO: JSON

  case class EmrSoftware(
    hbase: Option[String],
    lingual: Option[String])

  case class EmrJobflow(
    masterInstanceType: String,
    coreInstanceCount: Int,
    coreInstanceType: String,
    taskInstanceCount: Int,
    taskInstanceType: String,
    taskInstanceBid: BigDecimal)

  // collectors section
  import scala.reflect.runtime.universe._
  import scala.reflect.runtime.{ universe => ru }

  val m = ru.runtimeMirror(getClass.getClassLoader)

  sealed trait CollectorFormat { val asString: String }
  case object CloudfrontFormat extends CollectorFormat { val asString = "cloudfront" }
  case object ClojureTomcatFormat extends CollectorFormat { val asString = "clj-tomcat" }
  case object ThriftFormat extends CollectorFormat { val asString = "thrift" }
  case object CfAccessLogFormat extends CollectorFormat { val asString = "tsv/com.amazon.aws.cloudfront/wd_access_log" }
  case object UrbanAirshipConnectorFormat extends CollectorFormat { val asString = "ndjson/urbanairship.connect/v1" }

  case class Collectors(format: CollectorFormat)


  def sealedDescendants[Root: TypeTag]: Option[Set[Symbol]] = {
    val symbol = typeOf[Root].typeSymbol
    val internal = symbol.asInstanceOf[scala.reflect.internal.Symbols#Symbol]
    if (internal.isSealed)
      Some(internal.sealedDescendants.map(_.asInstanceOf[Symbol]) - symbol)
    else None
  }

  def getCaseObject(desc: Symbol): Any = {
    val mod = m.staticModule(desc.asClass.fullName)
    m.reflectModule(mod).instance
  }

  def fee[A: TypeTag]: Set[A] = {

    sealedDescendants[A].getOrElse(Set.empty).map(x => getCaseObject(x).asInstanceOf[A])
  }



  // enrich section

  case class Enrich(
    jobName: String,
    versions: EnrichVersions,
    continueOnUnexpectedError: Boolean,
    outputCompression: OutputCompression)

  case class EnrichVersions(
    hadoopEnrich: String,
    hadoopShred: String,
    hadoopElasticsearch: String)

  sealed trait OutputCompression { def asString: String }
  case object NoneCompression extends OutputCompression { val asString = "NONE" }
  case object GzipCompression extends OutputCompression { val asString = "GZIP" }

  // storage section

  case class Storage(download: Download)

  case class Download(folder: Option[String])

  // monitoring section

  case class Monitoring(
    tags: Map[String, String],
    logging: Logging,
    snowplow: SnowplowMonitoring)

  case class Logging(level: LoggingLevel)

  sealed trait LoggingLevel { def asString: String }
  case object DebugLevel extends LoggingLevel { val asString = "DEBUG" }
  case object InfoLevel extends LoggingLevel { val asString = "INFO" }

  case class SnowplowMonitoring(
    method: TrackerMethod,
    appId: String,
    collector: String)  // Host/port pair

  sealed trait TrackerMethod extends Product with Serializable { def asString: String }
  case object GetMethod extends TrackerMethod { val asString = "get" }
  case object PostMethod extends TrackerMethod { val asString = "post" }

  private implicit val decoderConfiguration =
    Configuration.default.withSnakeCaseKeys

  private def parse[A](read: String => Either[String, A])(hCursor: HCursor): Either[DecodingFailure, A] = {
    for {
      string <- hCursor.as[String]
      method  = read(string)
      result <- method.asDecodeResult(hCursor)
    } yield result
  }

  // Instances and helper methods

  object CollectorFormat {

    implicit val decodeCollectorFormat: Decoder[CollectorFormat] =
      Decoder.instance(parse(fromString))

    def fromString(string: String): Either[String, CollectorFormat] = string match {
      case CloudfrontFormat.asString            => CloudfrontFormat.asRight
      case ClojureTomcatFormat.asString         => ClojureTomcatFormat.asRight
      case ThriftFormat.asString                => ThriftFormat.asRight
      case CfAccessLogFormat.asString           => CfAccessLogFormat.asRight
      case UrbanAirshipConnectorFormat.asString => UrbanAirshipConnectorFormat.asRight
      case _                                    => s"Unknown collector format [$string]".asLeft
    }
  }

  object LoggingLevel {
    implicit val decodeLoggingLevel: Decoder[LoggingLevel] =
      Decoder.instance(parse(fromString))

    def fromString(string: String): Either[String, LoggingLevel] = string match {
      case DebugLevel.asString  => DebugLevel.asRight
      case InfoLevel.asString => InfoLevel.asRight
      case _                   => s"Unknown debug level [$string]".asLeft
    }
  }

  object TrackerMethod {
    implicit val decodeTrackerMethod: Decoder[TrackerMethod] =
      Decoder.instance(parse(fromString))

    def fromString(string: String): Either[String, TrackerMethod] = string match {
      case GetMethod.asString  => GetMethod.asRight
      case PostMethod.asString => PostMethod.asRight
      case _                   => s"Unknown tracking method [$string]".asLeft
    }
  }

  object Collectors {
    implicit val decodeCollectors: Decoder[Collectors] =
      Decoder.instance(parseCollectors)

    private def parseCollectors(hCursor: HCursor): Either[DecodingFailure, Collectors] = {
      for {
        jsonObject      <- hCursor.as[Map[String, Json]]
        format          <- jsonObject.getJson("format", hCursor)
        collectorFormat <- format.as[CollectorFormat]
      } yield Collectors(collectorFormat)
    }
  }

  object OutputCompression {
    implicit val decodeOutputCompression: Decoder[OutputCompression] =
      Decoder.instance(parse(fromString))

    def fromString(string: String): Either[String, OutputCompression] = string match {
      case NoneCompression.asString => NoneCompression.asRight
      case GzipCompression.asString => GzipCompression.asRight
      case _                        => s"Unknown output compression [$string]".asLeft
    }
  }

  object SnowplowMonitoring {
    import TrackerMethod._

    private[rdbloader] implicit val snowplowMonitoringDecoder: Decoder[SnowplowMonitoring] =
      ConfiguredDecoder.decodeCaseClass
  }
}
