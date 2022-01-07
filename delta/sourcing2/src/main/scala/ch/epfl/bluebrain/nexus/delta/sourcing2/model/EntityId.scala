package ch.epfl.bluebrain.nexus.delta.sourcing2.model

import doobie.{Get, Put}

/**
  * Entity identifier
  */
final case class EntityId private (value: String)

object EntityId {

  private val separator = "|"

  private def validate(value: String): Either[String, String] =
    Either.cond(!value.contains(separator), value, s"'$separator' is not a valid character.")

  def apply(value: String): Either[String, EntityId] = validate(value).map(unsafe)

  def unsafe(value: String): EntityId = new EntityId(value)

  implicit val entityIdGet: Get[EntityId] = Get[String].map(EntityId.unsafe)
  implicit val entityIdPut: Put[EntityId] = Put[String].contramap(_.value)

}
