package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.predicate
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, sh}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import io.circe.{Encoder, Json}
import org.apache.jena.query.DatasetFactory
import org.apache.jena.rdf.model.Resource

/**
  * Data type that represents the outcome of validating data against a shacl schema.
  *
  * @param conforms
  *   true if the validation was successful and false otherwise
  * @param targetedNodes
  *   the number of target nodes that were touched per shape
  * @param json
  *   the detailed message of the validator
  */
final case class ValidationReport private (conforms: Boolean, targetedNodes: Int, json: Json) {

  /**
    * @param ignoreTargetedNodes
    *   flag to decide whether or not ''targetedNodes'' should be ignored from the validation logic
    * @return
    *   true if the validation report has been successful or false otherwise
    */
  def isValid(ignoreTargetedNodes: Boolean = false): Boolean =
    (ignoreTargetedNodes && conforms) || (!ignoreTargetedNodes && targetedNodes > 0 && conforms)
}

object ValidationReport {

  private val shaclCtx: ContextValue = ContextValue(contexts.shacl)

  final def apply(report: Resource)(implicit api: JsonLdApi, rcr: RemoteContextResolution): IO[ValidationReport] = {
    val tmpGraph = Graph.unsafe(DatasetFactory.create(report.getModel).asDatasetGraph())
    for {
      rootNode      <-
        IO.fromEither(
          tmpGraph
            .find { case (_, p, _) => p == predicate(sh.conforms) }
            .map { case (s, _, _) => if (s.isURI) iri"${s.getURI}" else BNode(s.getBlankNodeLabel) }
            .toRight(new IllegalStateException("Unable to find predicate sh:conforms in the validation report graph"))
        )
      graph          = tmpGraph.replaceRootNode(rootNode)
      compacted     <- graph.toCompactedJsonLd(shaclCtx)
      json           = compacted.json
      conforms      <- IO.fromEither(json.hcursor.get[Boolean]("conforms"))
      targetedNodes <- IO.fromEither(json.hcursor.get[Int]("targetedNodes"))
    } yield ValidationReport(conforms, targetedNodes, json)
  }

  implicit val reportEncoder: Encoder[ValidationReport] = Encoder.instance(_.json)
}
