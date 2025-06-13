package ai.senscience.nexus.delta.plugins.graph.analytics.indexing

import ai.senscience.nexus.delta.plugins.graph.analytics.model.JsonLdDocument
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.syntax.{EncoderOps, KeyOps}
import io.circe.{Encoder, Json}

import java.time.Instant

/**
  * Result of the analysis for a result
  */
sealed trait GraphAnalyticsResult extends Product with Serializable

object GraphAnalyticsResult {

  /**
    * No action is required (i.e. it is not among the entity types we analyse)
    */
  final case object Noop extends GraphAnalyticsResult

  /**
    * An update by query action is required so that the type of this resource is propagated to the resources pointing to
    * it
    *
    * @param id
    *   the id of the resource
    * @param types
    *   the types of the resource
    */
  final case class UpdateByQuery(id: Iri, types: Set[Iri]) extends GraphAnalyticsResult

  /**
    * The value is indexed to Elasticsearch and an update by query action is required so that the type of this resource
    * is propagated to the resources pointing to it.
    *
    * The other fields are resource metadata.
    */
  final case class Index private (
      project: ProjectRef,
      id: Iri,
      remoteContexts: Set[RemoteContextRef],
      rev: Int,
      deprecated: Boolean,
      types: Set[Iri],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject,
      value: Option[JsonLdDocument]
  ) extends GraphAnalyticsResult

  object Index {

    def active(
        project: ProjectRef,
        id: Iri,
        remoteContexts: Set[RemoteContextRef],
        rev: Int,
        types: Set[Iri],
        createdAt: Instant,
        createdBy: Subject,
        updatedAt: Instant,
        updatedBy: Subject,
        value: JsonLdDocument
    ) =
      new Index(project, id, remoteContexts, rev, false, types, createdAt, createdBy, updatedAt, updatedBy, Some(value))

    def deprecated(
        project: ProjectRef,
        id: Iri,
        remoteContexts: Set[RemoteContextRef],
        rev: Int,
        types: Set[Iri],
        createdAt: Instant,
        createdBy: Subject,
        updatedAt: Instant,
        updatedBy: Subject
    ) =
      new Index(project, id, remoteContexts, rev, true, types, createdAt, createdBy, updatedAt, updatedBy, None)

    implicit val encoder: Encoder[Index] = Encoder.instance { i =>
      import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database.*
      Json
        .obj(
          keywords.id      := i.id,
          "remoteContexts" := i.remoteContexts,
          "_project"       := i.project,
          "_rev"           := i.rev,
          "_deprecated"    := i.deprecated,
          "_createdAt"     := i.createdAt,
          "_createdBy"     := i.createdBy,
          "_updatedAt"     := i.updatedAt,
          "_updatedBy"     := i.updatedBy
        )
        .addIfNonEmpty(keywords.tpe, i.types)
        .deepMerge(i.value.map(_.asJson).getOrElse(Json.obj()))
    }
  }

}
