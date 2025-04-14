package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.ParsingError
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}

object NTripleParser {

  private val logger = Logger[NTripleParser.type]

  def apply(ntriples: NTriples, rootNodeOpt: Option[Iri]): IO[Option[Graph]] =
    if (ntriples.isEmpty) {
      // If nothing is returned by the query, we skip
      IO.none
    } else
      {
        rootNodeOpt match {
          case Some(rootNode) =>
            IO.fromEither(Graph(ntriples.copy(rootNode = rootNode))).map { g =>
              Some(g.replaceRootNode(rootNode))
            }
          case None           =>
            IO.fromEither(Graph(ntriples)).map(Some(_))
        }
      }.onError {
        case p: ParsingError =>
          logger.error(p)("Blazegraph did not send back valid n-triples, please check the responses")
        case _               => IO.unit
      }
}
