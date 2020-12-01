package ch.epfl.bluebrain.nexus.delta.sdk.model

/**
  * The enumeration of the system resource types available
  */
sealed trait ResourceType extends Product with Serializable {

  /**
    * @return the name of the resource type
    */
  def name: Label
}
object ResourceType {

  /**
    * An Schema resource type
    */
  final case object SchemaResource extends ResourceType {
    override val name: Label = Label.unsafe("Schema")
  }

  /**
    * A Resolver resource type
    */
  final case object ResolverResource extends ResourceType {
    override val name: Label = Label.unsafe("Resolver")
  }

  /**
    * A data resource type
    */
  final case object DataResource extends ResourceType {
    override val name: Label = Label.unsafe("Resource")
  }

  /**
    * A resource type defined by a plugin
    */
  final case class PluginResource(name: Label) extends ResourceType
}
