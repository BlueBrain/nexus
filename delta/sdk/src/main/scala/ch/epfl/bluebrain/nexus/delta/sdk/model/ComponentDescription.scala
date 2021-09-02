package ch.epfl.bluebrain.nexus.delta.sdk.model

/**
  * The description of a component of the system (service or plugin)
  */
sealed trait ComponentDescription extends Product with Serializable {

  /**
    * @return
    *   the name of the component
    */
  def name: Name

  /**
    * @return
    *   the version of the component
    */
  def version: String
}

object ComponentDescription {

  private val unknownVersion = "unknown"

  /**
    * The description of a service
    */
  sealed trait ServiceDescription extends ComponentDescription

  object ServiceDescription {
    final case class ResolvedServiceDescription private (name: Name, version: String) extends ServiceDescription
    final case class UnresolvedServiceDescription private (name: Name)                extends ServiceDescription {
      override val version: String = unknownVersion
    }

    /**
      * Creates a [[ResolvedServiceDescription]] from the passed ''name'' and ''version''.
      */
    def apply(name: Name, version: String): ResolvedServiceDescription =
      ResolvedServiceDescription(name, version)

    /**
      * Creates a [[UnresolvedServiceDescription]] with the passed ''name''.
      */
    def unresolved(name: Name): UnresolvedServiceDescription =
      UnresolvedServiceDescription(name)
  }

  /**
    * The description of a plugin
    */
  final case class PluginDescription(name: Name, version: String) extends ComponentDescription {
    override def toString: String = s"$name-$version"
  }

}
