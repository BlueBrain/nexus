package ch.epfl.bluebrain.nexus.delta.sdk

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.owl
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection.InvalidSchemaResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.{Schema, SchemaRejection}
import monix.bio.IO

/**
  * Resolves the OWL imports from a Schema
  */
final class SchemaImports(resolveSchema: Resolve[Schema], resolveResource: Resolve[Resource]) { self =>

  /**
    * Resolve the ''imports'' from the passed ''expanded'' document and recursively from the resolved documents.
    *
    * @param id         the schema id
    * @param projectRef the project where the schema belongs to
    * @param expanded   the schema expanded form
    * @return a "fat-schema" with all the imports resolved
    */
  def resolve(id: Iri, projectRef: ProjectRef, expanded: ExpandedJsonLd)(implicit
      caller: Caller
  ): IO[SchemaRejection, ExpandedJsonLd] = {

    def detectNonOntology(resourceSuccess: Map[ResourceRef, Resource]): Set[ResourceRef] =
      resourceSuccess.collect {
        case (ref, r) if !r.expanded.cursor.getTypes.exists(_.contains(owl.Ontology)) => ref
      }.toSet

    def rejectOnLookupFailures(
        schemaRejections: Map[ResourceRef, ResourceResolutionReport],
        resourceRejections: Map[ResourceRef, ResourceResolutionReport],
        nonOntologies: Set[ResourceRef]
    ): IO[InvalidSchemaResolution, Unit] =
      if (resourceRejections.nonEmpty || nonOntologies.nonEmpty)
        IO.raiseError(InvalidSchemaResolution(id, schemaRejections, resourceRejections, nonOntologies))
      else IO.unit

    def lookupFromSchemasAndResources(toResolve: Set[ResourceRef]) = {
      for {
        (schemaRejections, schemaSuccess)     <- lookupInBatch(toResolve, resolveSchema(_, projectRef, caller))
        resourcesToResolve                     = toResolve -- schemaSuccess.keySet
        (resourceRejections, resourceSuccess) <-
          lookupInBatch(resourcesToResolve, resolveResource(_, projectRef, caller))
        nonOntologies                          = detectNonOntology(resourceSuccess)
        _                                     <- rejectOnLookupFailures(schemaRejections, resourceRejections, nonOntologies)
      } yield (
        schemaSuccess.keySet ++ resourceSuccess.keySet,
        schemaSuccess.values.map(_.expanded) ++ resourceSuccess.values.map(_.expanded)
      )
    }

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
