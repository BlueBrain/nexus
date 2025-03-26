package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import pureconfig.ConfigReader
import pureconfig.error.FailureReason

sealed trait SparqlTarget

object SparqlTarget {

  final case object Blazegraph extends SparqlTarget

  final case object Rdf4j extends SparqlTarget

  implicit val sparqlTargetReader: ConfigReader[SparqlTarget] = ConfigReader.stringConfigReader.emap {
    case "blazegraph" => Right(Blazegraph)
    case "rdf4j"      => Right(Rdf4j)
    case _            =>
      val reason = new FailureReason {
        override def description: String = s"Only 'blazegraph' and 'rdf4j' are valid sparql targets"
      }
      Left(reason)
  }
}
