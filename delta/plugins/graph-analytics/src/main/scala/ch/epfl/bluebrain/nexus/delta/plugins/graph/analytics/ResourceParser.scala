package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.ResourceParser.GraphDocument
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.JsonLdDocument
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.{DataResource, Resources}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import com.typesafe.scalalogging.Logger
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}
import monix.bio.UIO

/**
  * Fetches a given resource, parses its different fields and attempts to resolve any node reference to produce a
  * [[GraphDocument]]
  */
trait ResourceParser {

  def apply(id: Iri, projectRef: ProjectRef): UIO[Option[GraphDocument]]

}

object ResourceParser {

  private val logger: Logger = Logger[ResourceParser.type]

  /**
    * The document to be serialized and push in the Elasticsearch index
    */
  final case class GraphDocument(id: Iri, rev: Long, types: Set[Iri], value: JsonLdDocument)

  object GraphDocument {

    implicit val encoder: Encoder[GraphDocument] = Encoder.instance { g =>
      Json
        .obj(keywords.id -> g.id.asJson, "_rev" -> g.rev.asJson)
        .addIfNonEmpty(keywords.tpe, g.types)
        .deepMerge(
          g.value.asJson
        )
    }

  }

  def apply(resources: Resources, files: Files): ResourceParser =
    apply(
      (id: Iri, projectRef: ProjectRef) => resources.fetch(id, projectRef, None).redeem(_ => None, Some(_)),
      (id: Iri, projectRef: ProjectRef) => {
        resources
          .fetch(id, projectRef, None)
          .redeemWith(
            _ => files.fetch(id, projectRef).redeem(_ => None, r => Some(r.types)),
            r => UIO.some(r.types)
          )
      }
    )

  def apply(
      fetchResource: (Iri, ProjectRef) => UIO[Option[DataResource]],
      findRelationship: (Iri, ProjectRef) => UIO[Option[Set[Iri]]]
  ): ResourceParser =
    (id: Iri, projectRef: ProjectRef) =>
      fetchResource(id, projectRef).flatMap { resource =>
        resource.fold(
          UIO.delay(logger.warn(s"Resource '$id' in project '$projectRef' could not be found.")) >> UIO
            .none[GraphDocument]
        )(r =>
          JsonLdDocument.fromExpanded(r.value.expanded, findRelationship(_, projectRef)).map { document =>
            Some(
              GraphDocument(
                id,
                r.rev,
                r.types,
                document
              )
            )
          }
        )
      }
}
