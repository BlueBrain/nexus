package ch.epfl.bluebrain.nexus.delta.sourcing.model

/**
  * Defines a reference to another entity
  *
  * @param project
  *   the project where the entity lives
  * @param id
  *   the id of the referenced entity
  */
final case class EntityDependency private (project: ProjectRef, id: String)
