package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults.Binding
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF, ResourceRef, ResourceUris}
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json, JsonObject}

import java.time.Instant
import scala.util.Try

sealed trait SparqlLink {

  /**
    * @return
    *   the @id value of the resource
    */
  def id: Iri

  /**
    * @return
    *   the collection of types of this resource
    */
  def types: Set[Iri]

  /**
    * @return
    *   the paths from where the link has been found
    */
  def paths: List[Iri]
}

object SparqlLink {

  /**
    * A link that represents a managed resource on the platform.
    */
  final case class SparqlResourceLink(
      resource: ResourceF[List[Iri]]
  ) extends SparqlLink {

    override def id: Iri = resource.id

    override def types: Set[Iri] = resource.types

    override def paths: List[Iri] = resource.value
  }

  object SparqlResourceLink {

    private def projectRefFromId(id: Iri)(implicit base: BaseUri) =
      ProjectRef.parse(id.stripPrefix(base.iriEndpoint / "projects")).toOption

    private def resourceUrisFor(
        project: ProjectRef,
        schemaProject: ProjectRef,
        id: Iri,
        schema: ResourceRef,
        mappings: ApiMappings,
        projectBase: ProjectBase
    ): ResourceUris = ResourceUris.resource(project, schemaProject, id, schema)(mappings, projectBase)

    /**
      * Attempts to create a [[SparqlResourceLink]] from the given bindings
      *
      * @param bindings
      *   the sparql result bindings
      */
    def apply(
        bindings: Map[String, Binding],
        mappings: ApiMappings,
        projectBase: ProjectBase
    )(implicit base: BaseUri): Option[SparqlLink] =
      for {
        link         <- SparqlExternalLink(bindings)
        project      <-
          bindings.get(nxv.project.prefix).map(_.value).flatMap(Iri.absolute(_).toOption).flatMap(projectRefFromId)
        rev          <- bindings.get(nxv.rev.prefix).map(_.value).flatMap(v => Try(v.toLong).toOption)
        deprecated   <- bindings.get(nxv.deprecated.prefix).map(_.value).flatMap(v => Try(v.toBoolean).toOption)
        created      <- bindings.get(nxv.createdAt.prefix).map(_.value).flatMap(v => Try(Instant.parse(v)).toOption)
        updated      <- bindings.get(nxv.updatedAt.prefix).map(_.value).flatMap(v => Try(Instant.parse(v)).toOption)
        createdByIri <- bindings.get(nxv.createdBy.prefix).map(_.value).flatMap(Iri.absolute(_).toOption)
        createdBy    <- Subject.unsafe(createdByIri).toOption
        updatedByIri <- bindings.get(nxv.updatedBy.prefix).map(_.value).flatMap(Iri.absolute(_).toOption)
        updatedBy    <- Subject.unsafe(updatedByIri).toOption
        schema       <- bindings.get(nxv.constrainedBy.prefix).map(_.value).flatMap(Iri.absolute(_).toOption)
        schemaRef     = ResourceRef(schema)
        schemaProject = bindings
                          .get(nxv.schemaProject.prefix)
                          .map(_.value)
                          .flatMap(Iri.absolute(_).toOption)
                          .flatMap(projectRefFromId)
                          .getOrElse(project)
        resourceUris  = resourceUrisFor(project, schemaProject, link.id, schemaRef, mappings, projectBase)
      } yield SparqlResourceLink(
        ResourceF(
          link.id,
          resourceUris,
          rev,
          link.types,
          deprecated,
          created,
          createdBy,
          updated,
          updatedBy,
          schemaRef,
          link.paths
        )
      )

  }

  /**
    * A link that represents an external resource out of the platform.
    *
    * @param id
    *   the @id value of the resource
    * @param paths
    *   the predicate from where the link has been found
    * @param types
    *   the collection of types of this resource
    */
  final case class SparqlExternalLink(id: Iri, paths: List[Iri], types: Set[Iri] = Set.empty) extends SparqlLink

  object SparqlExternalLink {

    /**
      * Attempts to create a [[SparqlExternalLink]] from the given bindings
      *
      * @param bindings
      *   the sparql result bindings
      */
    def apply(bindings: Map[String, Binding]): Option[SparqlExternalLink] = {
      val types = bindings.get("types").map(binding => toIris(binding.value).toSet).getOrElse(Set.empty)
      val paths = bindings.get("paths").map(binding => toIris(binding.value).toList).getOrElse(List.empty)
      bindings.get("s").map(_.value).flatMap(Iri.absolute(_).toOption).map(SparqlExternalLink(_, paths, types))
    }
  }

  private def toIris(string: String): Array[Iri] =
    string.split(" ").flatMap(Iri.absolute(_).toOption)

  implicit def linkEncoder(implicit base: BaseUri): Encoder.AsObject[SparqlLink] = Encoder.AsObject.instance {
    case SparqlExternalLink(id, paths, types) =>
      JsonObject("@id" -> id.asJson, "@type" -> types.asJson, "paths" -> paths.asJson)
    case SparqlResourceLink(resource)         =>
      implicit val pathsEncoder: Encoder.AsObject[List[Iri]] =
        Encoder.AsObject.instance(paths => JsonObject("paths" -> Json.fromValues(paths.map(_.asJson))))
      resource.asJsonObject
  }
}
