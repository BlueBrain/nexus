package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import ch.epfl.bluebrain.nexus.delta.plugins.statistics.RelationshipResolution.Relationship
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.UIO

trait RelationshipResolution {

  /**
    * Attempts to resolve a relationship by its resource ''id'' on the passed ''projectRef''.
    *
    * @param projectRef the project where the project will be looked up
    * @param id         the id of the resource
    */
  def apply(projectRef: ProjectRef, id: Iri): UIO[Option[Relationship]]
}

object RelationshipResolution {

  final case class Relationship(id: Iri, types: Set[Iri])

  final def apply(exchanges: List[ReferenceExchange]): RelationshipResolution =
    new RelationshipResolution {
      override def apply(projectRef: ProjectRef, id: Iri): UIO[Option[Relationship]] =
        UIO
          .tailRecM(exchanges) { // try all reference exchanges one at a time until there's a result
            case Nil              => UIO.pure(Right(None))
            case exchange :: rest =>
              exchange.fetch(projectRef, ResourceRef.Latest(id)).map(_.toRight(rest).map(Some.apply))
          }
          .map(_.map(exchangeValue => Relationship(id, exchangeValue.resource.types)))
    }
}
