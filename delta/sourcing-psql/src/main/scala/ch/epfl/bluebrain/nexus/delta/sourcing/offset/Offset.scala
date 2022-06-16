package ch.epfl.bluebrain.nexus.delta.sourcing.offset

import cats.Order
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import doobie.implicits._
import doobie.util.fragment.Fragment
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import scala.annotation.nowarn

sealed trait Offset extends Product with Serializable {

  def after: Option[Fragment]

}

object Offset {

  /**
    * To fetch all rows from the beginning
    */
  final case object Start extends Offset {
    override def after: Option[Fragment] = None
  }

  /**
    * To fetch rows from the given offset
    */
  final case class At(value: Long) extends Offset {
    override def after: Option[Fragment] = Some(fr"ordering > $value")
  }

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
}
