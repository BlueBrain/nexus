package ch.epfl.bluebrain.nexus.delta.sourcing.implicits

import cats.Show
import cats.syntax.all.*
import cats.data.NonEmptyList
import doobie.util.{Get, Put}
import io.circe.jawn.parse
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json, Printer}
import org.postgresql.util.PGobject

/**
  * Instances to serialize Circe json values into a PostgresSQL jsonb column
  *
  * Derived from https://github.com/tpolecat/doobie/tree/main/modules/postgres-circe to be able to control the circe
  * printer
  */
trait CirceInstances {

  implicit private val showPGobject: Show[PGobject] = Show.show(_.getValue.take(250))

  implicit def jsonbPut: Put[Json] =
    jsonbPut(Printer.noSpaces)

  def jsonbPut(printer: Printer): Put[Json] =
    Put.Advanced
      .other[PGobject](
        NonEmptyList.of("jsonb")
      )
      .tcontramap { a =>
        val o = new PGobject
        o.setType("jsonb")
        o.setValue(printer.print(a))
        o
      }

  implicit val jsonbGet: Get[Json] =
    Get.Advanced
      .other[PGobject](
        NonEmptyList.of("jsonb")
      )
      .temap(a => parse(a.getValue).leftMap(_.show))

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
