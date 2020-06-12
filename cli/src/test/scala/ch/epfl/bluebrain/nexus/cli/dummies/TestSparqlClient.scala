package ch.epfl.bluebrain.nexus.cli.dummies

import java.io.ByteArrayOutputStream

import cats.effect.Sync
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.SerializationError
import ch.epfl.bluebrain.nexus.cli.ClientErrOr
import ch.epfl.bluebrain.nexus.cli.clients.{SparqlClient, SparqlResults}
import ch.epfl.bluebrain.nexus.cli.sse.{Event, OrgLabel, ProjectLabel}
import io.circe.Json
import org.http4s.Uri

class TestSparqlClient[F[_]](events: List[Event])(implicit F: Sync[F]) extends SparqlClient[F] {

  import io.circe.parser._
  import org.apache.jena.query.{Dataset, DatasetFactory, QueryFactory, ReadWrite, ResultSetFormatter, _}
  import org.apache.jena.rdf.model.{Model, ModelFactory}
  import org.apache.jena.riot.system.StreamRDFLib
  import org.apache.jena.riot.{Lang, RDFParser}

  private def toJenaModel(j: Json): Model = {
    val model  = ModelFactory.createDefaultModel()
    val stream = StreamRDFLib.graph(model.getGraph)
    RDFParser.create.fromString(j.noSpaces).lang(Lang.JSONLD).parse(stream)
    model
  }

  val ds: Dataset = DatasetFactory.createTxnMem()
  events.foreach { event =>
    val jsonGraph = event.raw.hcursor.get[Json]("_source").getOrElse(Json.obj())
    val graphUri  = event.resourceId.addSegment("graph").renderString
    val model     = toJenaModel(jsonGraph)
    ds.begin(ReadWrite.WRITE)
    try {
      ds.removeNamedModel(graphUri)
      ds.commit()
    } finally {
      ds.end()
    }
    ds.begin(ReadWrite.WRITE)
    try {
      ds.addNamedModel(graphUri, model)
      ds.commit()
    } finally {
      ds.end()
    }
  }
  ds.setDefaultModel(ds.getUnionModel)

  override def query(
      org: OrgLabel,
      proj: ProjectLabel,
      view: Option[Uri],
      queryStr: String
  ): F[ClientErrOr[SparqlResults]] = {
    F.delay {
      ds.begin(ReadWrite.READ)
      try {
        val query   = QueryFactory.create(queryStr)
        val qexec   = QueryExecutionFactory.create(query, ds.asDatasetGraph())
        val results = qexec.execSelect

        val outputStream = new ByteArrayOutputStream()
        ResultSetFormatter.outputAsJSON(outputStream, results)
        val json         = new String(outputStream.toByteArray)
        decode[SparqlResults](json).leftMap(_ =>
          SerializationError("Unable to decode sparql results", classOf[SparqlResults].getSimpleName, Some(json))
        )
      } finally {
        ds.end()
      }
    }
  }
}

object TestSparqlClient {

  final def apply[F[_]](events: List[Event])(implicit F: Sync[F]): F[TestSparqlClient[F]] =
    F.delay(new TestSparqlClient[F](events))

}
