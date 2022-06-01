package ch.epfl.bluebrain.nexus.delta.sdk

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.owl
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResourceResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection.InvalidSchemaResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{Schema, SchemaRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import monix.bio.IO

/**
  * Resolves the OWL imports from a Schema
  */
final class SchemaImports(resolveSchema: Resolve[Schema], resolveResource: Resolve[Resource]) { self =>

  /**
    * Resolve the ''imports'' from the passed ''expanded'' document and recursively from the resolved documents.
    *
    * @param id
    *   the schema id
    * @param projectRef
    *   the project where the schema belongs to
    * @param expanded
    *   the schema expanded form
    * @return
    *   a "fat-schema" with all the imports resolved
    */
  def resolve(id: Iri, projectRef: ProjectRef, expanded: ExpandedJsonLd)(implicit
      caller: Caller
  ): IO[SchemaRejection, NonEmptyList[ExpandedJsonLd]] = {

    def detectNonOntology(resourceSuccess: Map[ResourceRef, Resource]): Set[ResourceRef] =
      resourceSuccess.collect {
        case (ref, r) if !r.expanded.cursor.getTypes.exists(_.contains(owl.Ontology)) => ref
      }.toSet

    def rejectOnLookupFailures(
        schemaRejections: Map[ResourceRef, ResourceResolutionReport],
        resourceRejections: Map[ResourceRef, ResourceResolutionReport],
        nonOntologies: Set[ResourceRef]
    ): IO[InvalidSchemaResolution, Unit] =
      IO.when(resourceRejections.nonEmpty || nonOntologies.nonEmpty)(
        IO.raiseError(InvalidSchemaResolution(id, schemaRejections, resourceRejections, nonOntologies))
      )

    def lookupFromSchemasAndResources(
        toResolve: Set[ResourceRef]
    ): IO[InvalidSchemaResolution, Iterable[ExpandedJsonLd]] =
      for {
        (schemaRejections, schemaSuccess)     <- lookupInBatch(toResolve, resolveSchema(_, projectRef, caller))
        resourcesToResolve                     = toResolve -- schemaSuccess.keySet
        (resourceRejections, resourceSuccess) <-
          lookupInBatch(resourcesToResolve, resolveResource(_, projectRef, caller))
        nonOntologies                          = detectNonOntology(resourceSuccess)
        _                                     <- rejectOnLookupFailures(schemaRejections, resourceRejections, nonOntologies)
      } yield schemaSuccess.values.flatMap(_.expanded.value) ++ resourceSuccess.values.map(_.expanded)

    val imports   = expanded.cursor.downField(owl.imports).get[Set[ResourceRef]]
    val toResolve = imports.getOrElse(Set.empty)
    if (toResolve.isEmpty)
      IO.pure(NonEmptyList.of(expanded))
    else
      lookupFromSchemasAndResources(toResolve).map { documents =>
        NonEmptyList(expanded, documents.toList)
      }
  }

  private def lookupInBatch[A](toResolve: Set[ResourceRef], fetch: ResourceRef => IO[ResourceResolutionReport, A]) =
    toResolve.toList
      .parTraverse(ref => fetch(ref).bimap(ref -> _, ref -> _).attempt)
      .map(_.partitionMap(identity))
      .map { case (rejections, successes) => rejections.toMap -> successes.toMap }
}

object SchemaImports {

  /**
    * Construct a [[SchemaImports]].
    */
  final def apply(
      acls: Acls,
      resolvers: Resolvers,
      schemas: Schemas,
      resources: Resources
  ): SchemaImports = {
    def resolveSchema(ref: ResourceRef, projectRef: ProjectRef, caller: Caller)   =
      ResourceResolution
        .schemaResource(acls, resolvers, schemas)
        .resolve(ref, projectRef)(caller)
        .map(_.value)
    def resolveResource(ref: ResourceRef, projectRef: ProjectRef, caller: Caller) =
      ResourceResolution
        .dataResource(acls, resolvers, resources)
        .resolve(ref, projectRef)(caller)
        .map(_.value)
    new SchemaImports(resolveSchema, resolveResource)
  }

}
