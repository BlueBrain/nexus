package ch.epfl.bluebrain.nexus.delta.sourcing.model

import doobie.Put
import doobie.util.Get

sealed trait Tag extends Product with Serializable {

  def value: String

}

object Tag {

  sealed trait Latest extends Tag

  final case object Latest extends Latest {
    override def value: String = "latest"
  }

  final case class UserTag private (value: String) extends Tag

  object UserTag {

    def unsafe(value: String) = new UserTag(value)

    def apply(value: String): Either[String, UserTag] =
      Either.cond(value == "latest", new UserTag(value), "'latest' is a reserved tag")
  }

  implicit val tagGet: Get[Tag] = Get[String].map {
    case "latest" => Latest
    case s        => UserTag.unsafe(s)
  }

  implicit val tagPut: Put[Tag] = Put[String].contramap(_.value)
}
