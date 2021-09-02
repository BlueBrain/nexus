package ch.epfl.bluebrain.nexus.delta.sdk.model

/**
  * The entity type. Examples of this can be ''resources'', ''resolvers'', ... This value is then used to construct the
  * persistence_id prefix for this particular module.
  */
final case class EntityType(value: String) extends AnyVal {
  override def toString: String = value
}
