package ch.epfl.bluebrain.nexus.delta.sourcing.offset

import cats.Order
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.FragmentEncoder
import doobie._
import doobie.implicits._
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import scala.annotation.nowarn

sealed trait Offset extends Product with Serializable {

  def value: Long

  def ordering: Long = this match {
    case Offset.Start     => 0L
    case Offset.At(value) => value
  }
}

object Offset {

  /**
    * To fetch all rows from the beginning
    */
  final case object Start extends Offset {
    override val value: Long = 0L
  }

  def from(value: Long): Offset = if (value > 0L) Offset.at(value) else Offset.Start

  /**
    * To fetch rows from the given offset
    */
  final case class At(value: Long) extends Offset

  val start: Offset = Start

  def at(value: Long): Offset = At(value)

  implicit val offsetOrder: Order[Offset] = Order.from {
    case (Start, Start) => 0
    case (Start, At(_)) => -1
    case (At(_), Start) => 1
    case (At(x), At(y)) => Order.compare(x, y)
  }

  implicit final val offsetCodec: Codec[Offset] = {
    @nowarn("cat=unused")
    implicit val configuration: Configuration =
      Configuration.default.withDiscriminator(keywords.tpe)
    deriveConfiguredCodec[Offset]
  }

  implicit final val offsetGet: Get[Offset] = Get[Long].map(from)
  implicit final val offsetPut: Put[Offset] = Put[Long].contramap(_.value)

  implicit val offsetFragmentEncoder: FragmentEncoder[Offset] = FragmentEncoder.instance {
    case Start     => None
    case At(value) => Some(fr"ordering > $value")
  }

  implicit val offsetJsonLdEncoder: JsonLdEncoder[Offset] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.offset))

}
