package ch.epfl.bluebrain.nexus.cli

import java.io.ByteArrayOutputStream

import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.{NexusConfig, NexusEndpoints}
import ch.epfl.bluebrain.nexus.cli.error.ClientError
import ch.epfl.bluebrain.nexus.cli.error.ClientError.SerializationError
import ch.epfl.bluebrain.nexus.cli.types.{Event, Label, SparqlResults}
import io.chrisdavenport.log4cats.Logger
import io.circe.Json
import org.http4s._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import retry.CatsEffect._
import retry.syntax.all._
import retry.{RetryDetails, RetryPolicy}

trait SparqlClient[F[_]] {

  /**
    * Performs a SPARQL query on the default view of the passed organization and project.
    *
    * @param organization the organization label
    * @param project      the project label
    * @param value        the SPARQL query
    */
  def query(organization: Label, project: Label, value: String): F[ClientErrOr[SparqlResults]] =
    query(organization, project, SparqlClient.defaultSparqlView, value)

  /**
    * Performs a SPARQL query on the passed view, organization and project.
    *
    * @param organization the organization label
    * @param project      the project label
    * @param viewId       the view @id value
    * @param value        the SPARQL query
    */
  def query(organization: Label, project: Label, viewId: Uri, value: String): F[ClientErrOr[SparqlResults]]
}

object SparqlClient {

  final val defaultSparqlView: Uri =
    Uri.unsafeFromString("https://bluebrain.github.io/nexus/vocabulary/defaultSparqlIndex")

  final val `application/sparql-query`: MediaType =
    new MediaType("application", "sparql-query")

  final private[cli] class LiveSparqlClient[F[_]: Sync: Timer](client: Client[F], config: NexusConfig)
      extends SparqlClient[F] {
    private val retry                                = config.httpClient.retry
    private val endpoints                            = NexusEndpoints(config)
    private val successCondition                     = retry.retryCondition.notRetryFromEither[SparqlResults] _
    implicit private val retryPolicy: RetryPolicy[F] = retry.retryPolicy
    implicit private val logOnError: (ClientErrOr[SparqlResults], RetryDetails) => F[Unit] =
      (eitherErr, details) => Logger[F].info(s"Client error '$eitherErr'. Retry details: '$details'")

    def query(
        organization: Label,
        project: Label,
        viewId: Uri,
        value: String
    ): F[ClientErrOr[SparqlResults]] = {
      val uri     = endpoints.sparqlQueryUri(organization, project, viewId)
      val headers = Headers(config.authorizationHeader.toList)
      val req = Request[F](method = Method.POST, uri = uri, headers = headers)
        .withEntity(value)
        .withContentType(`Content-Type`(`application/sparql-query`))
      val resp: F[ClientErrOr[SparqlResults]] = client.fetch(req)(ClientError.errorOr { r =>
        r.attemptAs[SparqlResults].value.map(_.leftMap(err => SerializationError(err.message, "SparqlResults")))
      })
      resp.retryingM(successCondition)
    }
  }

  final private[cli] class TestSparqlClient[F[_]](value: Map[ProjectLabelRef, SparqlResults])(implicit F: Sync[F])
      extends SparqlClient[F] {
    private val notFound: ClientError = ClientError.unsafe(Status.NotFound, "Project not found")
    def query(organization: Label, project: Label, viewId: Uri, query: String): F[ClientErrOr[SparqlResults]] =
      F.delay(value.get((organization, project)).toRight(notFound))
  }

  final private[cli] class JenaTestSparqlClient[F[_]](events: List[Event])(implicit F: Sync[F])
      extends SparqlClient[F] {

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
        organization: Label,
        project: Label,
        viewId: Uri,
        value: String
    ): F[ClientErrOr[SparqlResults]] = {
      F.delay {
        ds.begin(ReadWrite.READ)
        try {
          val query   = QueryFactory.create(value)
          val qexec   = QueryExecutionFactory.create(query, ds.asDatasetGraph())
          val results = qexec.execSelect

          val outputStream = new ByteArrayOutputStream()
          ResultSetFormatter.outputAsJSON(outputStream, results)
          val json = new String(outputStream.toByteArray)
          decode[SparqlResults](json).leftMap(_ =>
            SerializationError("Unable to decode sparql results", classOf[SparqlResults].getSimpleName, Some(json))
          )
        } finally {
          ds.end()
        }
      }
    }
  }

  /**
    * Construct a [[SparqlClient]] to query Nexus sparql view.
    *
    * @param client the underlying HTTP client
    * @param config the Nexus configuration
    */
  final def apply[F[_]: Sync: Timer](client: Client[F], config: NexusConfig): SparqlClient[F] =
    new LiveSparqlClient(client, config)

}
