package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

/**
  * Enumeration of resolver types
  */
sealed trait ResolverType extends Product with Serializable

object ResolverType {

  /**
    * Resolver within a same project
    */
  case object InProject extends ResolverType

  /**
    * Resolver across multiple projects
    */
  case object CrossProject extends ResolverType

}
