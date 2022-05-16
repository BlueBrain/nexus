package ch.epfl.bluebrain.nexus.delta.sourcing.model

import doobie.Put
import doobie.util.Get

sealed trait Tag extends Product with Serializable {

  def value: String

}

object Tag {

  final case object Latest extends Tag {
    override def value: String = "latest"
  }

  final case class UserTag private (value: String) extends Tag

  def apply(value: String): Either[String, Tag] =
    Either.cond(value == "latest", UserTag(value), "'latest' is a reserved tag")

  implicit val tagGet: Get[Tag] = Get[String].map {
    case "latest" => Latest
    case s        => UserTag(s)
  }
  implicit val tagPut: Put[Tag] = Put[String].contramap(_.value)
}
