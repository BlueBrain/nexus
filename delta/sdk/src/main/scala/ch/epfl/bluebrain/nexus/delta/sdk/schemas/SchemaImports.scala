package ch.epfl.bluebrain.nexus.delta.sdk.schemas

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.owl
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.Resolve
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.{Resolvers, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.SchemaRejection.InvalidSchemaResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

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
  ): IO[NonEmptyList[ExpandedJsonLd]] = {

    def detectNonOntology(resourceSuccess: Map[ResourceRef, Resource]): Set[ResourceRef] =
      resourceSuccess.collect {
        case (ref, r) if !r.expanded.cursor.getTypes.exists(_.contains(owl.Ontology)) => ref
      }.toSet

    def rejectOnLookupFailures(
        schemaRejections: Map[ResourceRef, ResourceResolutionReport],
        resourceRejections: Map[ResourceRef, ResourceResolutionReport],
        nonOntologies: Set[ResourceRef]
    ): IO[Unit] =
      IO.raiseWhen(resourceRejections.nonEmpty || nonOntologies.nonEmpty)(
        InvalidSchemaResolution(id, schemaRejections, resourceRejections, nonOntologies)
      )

    def lookupFromSchemasAndResources(
        toResolve: Set[ResourceRef]
    ): IO[Iterable[ExpandedJsonLd]] =
      for {
        (schemaRejections, schemaSuccess)     <- lookupInBatch(toResolve, resolveSchema(_, projectRef, caller))
        resourcesToResolve                     = toResolve -- schemaSuccess.keySet
        (resourceRejections, resourceSuccess) <-
          lookupInBatch(resourcesToResolve, resolveResource(_, projectRef, caller))
        nonOntologies                          = detectNonOntology(resourceSuccess)
        _                                     <- rejectOnLookupFailures(schemaRejections, resourceRejections, nonOntologies)
      } yield schemaSuccess.values.flatMap(_.expanded.toList) ++ resourceSuccess.values.map(_.expanded)

    val imports   = expanded.cursor.downField(owl.imports).get[Set[ResourceRef]]
    val toResolve = imports.getOrElse(Set.empty)
    if (toResolve.isEmpty)
      IO.pure(NonEmptyList.of(expanded))
    else
      lookupFromSchemasAndResources(toResolve).map { documents =>
        NonEmptyList(expanded, documents.toList)
      }
  }

  private def lookupInBatch[A](
      toResolve: Set[ResourceRef],
      fetch: ResourceRef => IO[Either[ResourceResolutionReport, A]]
  ): IO[(Map[ResourceRef, ResourceResolutionReport], Map[ResourceRef, A])] =
    toResolve.toList
      .parTraverse { ref => fetch(ref).map(_.bimap(ref -> _, ref -> _)) }
      .map(_.partitionMap(identity))
      .map { case (rejections, successes) => rejections.toMap -> successes.toMap }
}

object SchemaImports {

  final def alwaysFail = new SchemaImports(
    (_, _, _) => IO.pure(Left(ResourceResolutionReport())),
    (_, _, _) => IO.pure(Left(ResourceResolutionReport()))
  )

  /**
    * Construct a [[SchemaImports]].
    */
  final def apply(
      aclCheck: AclCheck,
      resolvers: Resolvers,
      schemas: Schemas,
      fetchResource: FetchResource
  ): SchemaImports = {
    def resolveSchema(ref: ResourceRef, projectRef: ProjectRef, caller: Caller) =
      ResourceResolution
        .schemaResource(aclCheck, resolvers, schemas, excludeDeprecated = true)
        .resolve(ref, projectRef)(caller)
        .map(_.map(_.value))

    def resolveResource(ref: ResourceRef, projectRef: ProjectRef, caller: Caller) =
      ResourceResolution
        .dataResource(aclCheck, resolvers, fetchResource, excludeDeprecated = true)
        .resolve(ref, projectRef)(caller)
        .map(_.map(_.value))

    new SchemaImports(resolveSchema, resolveResource)
  }

}
