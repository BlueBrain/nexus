package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv

/**
  * Enumeration of composite view source types
  */
sealed trait SourceType {

  /**
    * @return the type id
    */
  def tpe: Iri

  /**
    * @return the full set of types
    */
  def types: Set[Iri] = Set(tpe, nxv + "CompositeViewSource")

}

object SourceType {

  /**
    * A source for the current project.
    */
  final case object ProjectSourceType extends SourceType {
    override val toString: String = "ProjectSource"
    override val tpe: Iri         = nxv + toString
  }

  /**
    * A cross project source.
    */
  final case object CrossProjectSourceType extends SourceType {
    override val toString: String = "CrossProjectSource"
    override val tpe: Iri         = nxv + toString
  }

  /**
    * A remote project source.
    */
  final case object RemoteProjectSourceType extends SourceType {
    override val toString: String = "RemoteProjectSource"
    override val tpe: Iri         = nxv + toString
  }
}
