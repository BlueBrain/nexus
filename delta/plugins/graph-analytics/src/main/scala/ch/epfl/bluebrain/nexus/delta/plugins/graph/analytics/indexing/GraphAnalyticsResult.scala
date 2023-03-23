package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.JsonLdDocument
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.{Encoder, Json}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.syntax.EncoderOps

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
    * The other fields are metadata and match the same definition as in [[ResourceState]].
    *
    * @see
    *   [[ResouceState]]
    */
  final case class Index(
      project: ProjectRef,
      id: Iri,
      rev: Int,
      types: Set[Iri],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject,
      value: JsonLdDocument
  ) extends GraphAnalyticsResult

  object Index {
    implicit val encoder: Encoder[Index] = Encoder.instance { g =>
      import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
      Json
        .obj(
          keywords.id  -> g.id.asJson,
          "_project"   -> g.project.asJson,
          "_rev"       -> g.rev.asJson,
          "_createdAt" -> g.createdAt.asJson,
          "_createdBy" -> g.createdBy.asJson,
          "_updatedAt" -> g.updatedAt.asJson,
          "_updatedBy" -> g.updatedBy.asJson
        )
        .addIfNonEmpty(keywords.tpe, g.types)
        .deepMerge(g.value.asJson)
    }
  }

}
