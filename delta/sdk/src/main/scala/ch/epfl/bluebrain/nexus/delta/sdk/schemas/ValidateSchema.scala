package ch.epfl.bluebrain.nexus.delta.sdk.schemas

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.InvalidJsonLdFormat
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.{ShaclEngine, ShaclShapesGraph, ValidationReport}

trait ValidateSchema {

  def apply(id: Iri, expanded: NonEmptyList[ExpandedJsonLd]): IO[ValidationReport]

}

object ValidateSchema {

  def apply(implicit api: JsonLdApi, shaclShapesGraph: ShaclShapesGraph, rcr: RemoteContextResolution): ValidateSchema =
    new ValidateSchema {
      override def apply(id: Iri, expanded: NonEmptyList[ExpandedJsonLd]): IO[ValidationReport] = {
        for {
          graph  <- toGraph(id, expanded)
          report <- ShaclEngine(graph, reportDetails = true)
        } yield report
      }

      private def toGraph(id: Iri, expanded: NonEmptyList[ExpandedJsonLd]) = {
        val eitherGraph =
          toFoldableOps(expanded)
            .foldM(Graph.empty)((acc, expandedEntry) => expandedEntry.toGraph.map(acc ++ (_: Graph)))
            .leftMap { err => InvalidJsonLdFormat(Some(id), err) }
        IO.fromEither(eitherGraph)
      }
    }

}
