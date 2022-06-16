package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ElemCtx.SourceId
import fs2.Stream
import io.circe.{Json, JsonObject}
import monix.bio.Task
import shapeless.Typeable

import java.time.Instant
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

class Strings(override val id: Iri, length: Int, until: Int, sleepAtEnd: FiniteDuration) extends Source {
  require(length > 0, "Length configuration must be a strict positive Integer")
  require(until >= 0, "Until configuration must be a positive Integer")
  override type Out = String
  override def label: Label              = Strings.label
  override def outType: Typeable[String] = Typeable[String]

  override def apply(offset: ProjectionOffset): Stream[Task, Elem[String]] =
    offset.forSource(this) match {
      case Offset.Start     => buildStream(0)
      case Offset.At(value) => buildStream(value.toInt)
    }

  private def buildStream(start: Int) =
    Stream
      .iterate(start)(_ + 1)
      .take(until.toLong)
      .map(e => elemFor(e))
      .covary[Task] ++ terminal

  private def elemFor(idx: Int): Elem[String] =
    SuccessElem[String](
      SourceId(id),
      EntityType("String"),
      iri"http://localhost/$idx",
      1,
      Instant.now(),
      Offset.at(idx.toLong),
      genString(length)
    )

  private val terminal = Stream
    .sleep[Task](sleepAtEnd)
    .map(_ => elemFor(Int.MaxValue))

  private def genString(length: Int): String = {
    val pool = Vector.range('a', 'z')
    val size = pool.size

    @tailrec
    def inner(acc: String, remaining: Int): String =
      if (remaining <= 0) acc
      else inner(acc + pool(Random.nextInt(size)), remaining - 1)

    inner("", length)
  }
}

object Strings extends SourceDef {
  override type SourceType = Strings
  override type Config     = StringsConfig
  override def configType: Typeable[StringsConfig]                 = Typeable[StringsConfig]
  override def configDecoder: JsonLdDecoder[StringsConfig]         = StringsConfig.stringsConfigJsonLdDecoder
  override def label: Label                                        = Label.unsafe("strings")
  override def withConfig(config: StringsConfig, id: Iri): Strings =
    new Strings(id, config.length, config.max, config.sleepAtEnd)

  final case class StringsConfig(length: Int, max: Int, sleepAtEnd: FiniteDuration) {
    def toJsonLd: ExpandedJsonLd = ExpandedJsonLd(
      Seq(
        ExpandedJsonLd.unsafe(
          BNode.random,
          JsonObject(
            (nxv + "length").toString     -> Json.arr(Json.obj("@value" -> Json.fromInt(max))),
            (nxv + "max").toString        -> Json.arr(Json.obj("@value" -> Json.fromInt(max))),
            (nxv + "sleepAtEnd").toString -> Json.arr(Json.obj("@value" -> Json.fromString(sleepAtEnd.toString())))
          )
        )
      )
    )
  }

  object StringsConfig {
    implicit val stringsConfigJsonLdDecoder: JsonLdDecoder[StringsConfig] =
      deriveJsonLdDecoder[StringsConfig]
  }
}
