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

  def value: Long

  def asFragment: Option[Fragment]

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

    override def asFragment: Option[Fragment] = None
  }

  def from(value: Long): Offset = if(value > 0L) Offset.at(value) else Offset.Start

  /**
    * To fetch rows from the given offset
    */
  final case class At(value: Long) extends Offset {
    override def asFragment: Option[Fragment] = Some(fr"ordering > $value")
  }

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
}
