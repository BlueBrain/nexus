package ch.epfl.bluebrain.nexus.delta.sourcing2.model

import doobie.{Get, Put}

/**
  * Entity scope
  *
  * Defines a unique scope for entity ids
  */
final case class EntityScope(value: String) extends AnyVal

object EntityScope {
  implicit val entityScopeGet: Get[EntityScope] = Get[String].map(EntityScope(_))
  implicit val entityScopePut: Put[EntityScope] = Put[String].contramap(_.value)
}
