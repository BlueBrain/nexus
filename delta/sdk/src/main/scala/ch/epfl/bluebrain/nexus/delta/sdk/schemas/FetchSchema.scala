package ch.epfl.bluebrain.nexus.delta.sdk.schemas

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.cache.{CacheConfig, LocalCache}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Fetch.FetchF
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.{RevisionNotFound, SchemaNotFound, TagNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.{Schema, SchemaRejection, SchemaState}
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEventLogReadOnly
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

trait FetchSchema {

  /** Fetch the referenced schema in the given project */
  def apply(ref: ResourceRef, project: ProjectRef): IO[SchemaResource]

  def option(ref: ResourceRef, project: ProjectRef): FetchF[Schema] = apply(ref, project).option
}

object FetchSchema {

  def cached(underlying: FetchSchema, config: CacheConfig): IO[FetchSchema] =
    LocalCache[(ResourceRef, ProjectRef), SchemaResource](config).map {
      cache => (ref: ResourceRef, project: ProjectRef) =>
        cache.getOrElseUpdate((ref, project), underlying(ref, project))
    }

  def apply(log: ScopedEventLogReadOnly[Iri, SchemaState, SchemaRejection]): FetchSchema = {

    def notFound(iri: Iri, project: ProjectRef) = SchemaNotFound(iri, project)

    (ref: ResourceRef, project: ProjectRef) =>
      {
        ref match {
          case Latest(iri)           => log.stateOr(project, iri, notFound(iri, project))
          case Revision(_, iri, rev) => log.stateOr(project, iri, rev, notFound(iri, project), RevisionNotFound)
          case Tag(_, iri, tag)      => log.stateOr(project, iri, tag, notFound(iri, project), TagNotFound(tag))
        }
      }.map(_.toResource)
  }
}
