package ch.epfl.bluebrain.nexus.delta.kernel.dependency

/**
  * The description of a component of the system (service or plugin)
  */
sealed trait ComponentDescription extends Product with Serializable {

  /**
    * @return
    *   the name of the component
    */
  def name: String

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
    final case class ResolvedServiceDescription private[ServiceDescription] (name: String, version: String)
        extends ServiceDescription
    final case class UnresolvedServiceDescription private[ServiceDescription] (name: String)
        extends ServiceDescription {
      override val version: String = unknownVersion
    }

    /**
      * Creates a [[ResolvedServiceDescription]] from the passed ''name'' and ''version''.
      */
    def apply(name: String, version: String): ResolvedServiceDescription =
      ResolvedServiceDescription(name, version)

    /**
      * Creates a [[UnresolvedServiceDescription]] with the passed ''name''.
      */
    def unresolved(name: String): UnresolvedServiceDescription =
      UnresolvedServiceDescription(name)
  }

  /**
    * The description of a plugin
    */
  final case class PluginDescription(name: String, version: String) extends ComponentDescription {
    override def toString: String = s"$name-$version"
  }

}
