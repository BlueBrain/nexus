package ch.epfl.bluebrain.nexus.delta.sdk

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.owl
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaImports.Fetch
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection.{InvalidSchemaResolution, WrappedResolverResolutionRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{Schema, SchemaRejection}
import monix.bio.IO

/**
  * Resolves the OWL imports from a Schema
  */
final class SchemaImports private[sdk] (fetchSchema: Fetch[Schema], fetchResource: Fetch[Resource]) { self =>

  /**
    * Resolve the ''imports'' from the passed ''expanded'' document and recursively from the resolved documents.
    *
    * @param id         the schema id
    * @param projectRef the project where the schema belongs to
    * @param expanded   the schema expanded form
    * @return a "fat-schema" with all the imports resolved
    */
  def resolve(id: Iri, projectRef: ProjectRef, expanded: ExpandedJsonLd): IO[SchemaRejection, ExpandedJsonLd] = {

    def rejectOnNonOntology(resourceSuccess: Map[ResourceRef, Resource]) =
      resourceSuccess.collect {
        case (ref, r) if !r.expanded.cursor.getTypes.exists(_.contains(owl.Ontology)) => ref
      } match {
        case nonOntology if nonOntology.isEmpty => IO.unit
        case nonOntology                        =>
          IO.raiseError(InvalidSchemaResolution(id, nonOntology, Some("Resource imports must be ontologies")))
      }

    def rejectOnLookupFailures(failedRefs: Set[ResourceRef]) =
      if (failedRefs.nonEmpty) IO.raiseError(InvalidSchemaResolution(id, failedRefs))
      else IO.unit

    def lookupFromSchemasAndResources(toResolve: Set[ResourceRef]) =
      for {
        (_, schemaSuccess)            <- lookupInBatch(toResolve, fetchSchema(projectRef, _))
        resourcesToResolve             = toResolve -- schemaSuccess.keySet
        (rejections, resourceSuccess) <- lookupInBatch(resourcesToResolve, fetchResource(projectRef, _))
        _                             <- rejectOnLookupFailures(rejections)
        _                             <- rejectOnNonOntology(resourceSuccess)
      } yield (
        schemaSuccess.keySet ++ resourceSuccess.keySet,
        schemaSuccess.values.map(_.expanded) ++ resourceSuccess.values.map(_.expanded)
      )

    def recurse(
        resolved: Set[ResourceRef],
        document: ExpandedJsonLd
    ): IO[SchemaRejection, (Set[ResourceRef], ExpandedJsonLd)] = {
      val imports   = document.cursor.downField(owl.imports).get[Set[ResourceRef]]
      val toResolve = imports.getOrElse(Set.empty) -- resolved
      if (toResolve.isEmpty)
        IO.pure((resolved, document))
      else
        for {
          (refs, documents)                <- lookupFromSchemasAndResources(toResolve)
          resolvedAcc                       = resolved ++ refs
          recursed                         <- documents.toList.traverse(recurse(resolvedAcc, _))
          (recursedRefs, recursedDocuments) = recursed.unzip
        } yield (resolvedAcc ++ recursedRefs.flatten, ExpandedJsonLd(document :: recursedDocuments))
    }

    recurse(Set(ResourceRef(id)), expanded).map { case (_, expanded) => expanded }
  }

  private def lookupInBatch[A](toResolve: Set[ResourceRef], fetch: ResourceRef => IO[SchemaRejection, A]) =
    toResolve.toList
      .parTraverse(ref => fetch(ref).bimap(ref -> _, ref -> _).attempt)
      .map(_.partitionMap(identity))
      .map { case (rejections, successes) => rejections.toMap.keySet -> successes.toMap }
}

object SchemaImports {
  private[sdk] type Fetch[A] = (ProjectRef, ResourceRef) => IO[SchemaRejection, A]

  /**
    * Construct a [[SchemaImports]] from the resolvers bundle.
    */
  final def apply(resolvers: Resolvers)(implicit
      mapper: Mapper[ResolverResolutionRejection, WrappedResolverResolutionRejection]
  ): SchemaImports =
    new SchemaImports(
      resolvers.fetchSchema[WrappedResolverResolutionRejection],
      resolvers.fetchResource[WrappedResolverResolutionRejection]
    )

}
