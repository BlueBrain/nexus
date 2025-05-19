package ch.epfl.bluebrain.nexus.delta.sourcing.implicits

import cats.data.NonEmptyList
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.CirceInstances.{defaultWriterConfig, jsonSourceCodec}
import com.github.plokhotnyuk.jsoniter_scala.circe.JsoniterScalaCodec
import com.github.plokhotnyuk.jsoniter_scala.core.*
import doobie.util.{Get, Put}
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import org.postgresql.util.PGobject

/**
  * Instances to serialize Circe json values into a PostgresSQL jsonb column
  *
  * Derived from https://github.com/tpolecat/doobie/tree/main/modules/postgres-circe to be able to control the circe
  * printer
  */
trait CirceInstances {

  implicit def jsonbPut: Put[Json] =
    jsonbPut(jsonSourceCodec)

  def write(value: Json)(implicit codec: JsonValueCodec[Json]): String =
    writeToString(value, defaultWriterConfig)

  def read(value: String): Json =
    readFromString(value)(jsonSourceCodec)

  def jsonbPut(implicit codec: JsonValueCodec[Json]): Put[Json] =
    Put.Advanced
      .other[PGobject](
        NonEmptyList.of("jsonb")
      )
      .tcontramap { a =>
        val o = new PGobject
        o.setType("jsonb")
        o.setValue(write(a))
        o
      }

  implicit val jsonbGet: Get[Json] =
    Get.Advanced
      .other[PGobject](
        NonEmptyList.of("jsonb")
      )
      .map(a => read(a.getValue))

  def pgEncoderPutT[A: Encoder]: Put[A] =
    Put[Json].tcontramap(_.asJson)

  def pgEncoderPut[A: Encoder]: Put[A] =
    Put[Json].contramap(_.asJson)

  def pgDecoderGetT[A: Decoder]: Get[A] =
    Get[Json].temap(json => json.as[A].leftMap(_.show))

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def pgDecoderGet[A: Decoder]: Get[A] =
    Get[Json].map(json => json.as[A].fold(throw _, identity))
}

object CirceInstances extends CirceInstances {

  private val defaultWriterConfig: WriterConfig = WriterConfig.withPreferredBufSize(100 * 1024)

  val jsonCodecDropNull: JsonValueCodec[Json] =
    JsoniterScalaCodec.jsonCodec(maxDepth = 512, doSerialize = _ ne Json.Null)
  val jsonSourceCodec: JsonValueCodec[Json]   = JsoniterScalaCodec.jsonCodec(maxDepth = 512)
}
