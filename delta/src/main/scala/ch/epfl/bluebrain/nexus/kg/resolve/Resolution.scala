package ch.epfl.bluebrain.nexus.kg.resolve

import ch.epfl.bluebrain.nexus.kg.resources.{Ref, Resource}

/**
  * Type definition that handles the resolution process of references to resources.
  *
  * @tparam F the resolution effect type
  */
trait Resolution[F[_]] {

  /**
    * Attempts to resolve the argument reference to a resource. If the resolution process yields multiple values the
    * first one is returned.
    *
    * @param ref the reference to resolve
    * @return an optional resolved resource in F
    */
  def resolve(ref: Ref): F[Option[Resource]]
}
