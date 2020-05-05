package ch.epfl.bluebrain.nexus.cli.clients

import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.SerializationError
import ch.epfl.bluebrain.nexus.cli._
import ch.epfl.bluebrain.nexus.cli.config.influx.InfluxConfig
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, EnvConfig}
import io.circe.Json
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.{Method, Request, UrlForm}
import retry.CatsEffect._
import retry.syntax.all._
import retry.{RetryDetails, RetryPolicy}

trait InfluxClient[F[_]] {

  /**
    * Create a new InfluxDb database.
    */
  def createDb: F[Unit]

  /**
    * Retrieves information about the influxDB health.
    */
  def health: F[ClientErrOr[Json]]

  /**
    * Write an InfluxDB point to a database.
    *
    * @param point the influxDB line protocol point to write
    */
  def write(point: InfluxPoint): F[ClientErrOr[Unit]]

  /**
    * Query data with influxQL
    * @param ql the query in influxQL query language
    */
  def query(ql: String): F[ClientErrOr[Json]]

}

object InfluxClient {

  private class LiveInfluxDbClient[F[_]: Timer](
      client: Client[F],
      console: Console[F],
      config: InfluxConfig,
      env: EnvConfig
  )(implicit val F: Sync[F])
      extends InfluxClient[F] {
    private val retry                                = env.httpClient.retry
    implicit private def successCondition[A]         = retry.condition.notRetryFromEither[A] _
    implicit private val retryPolicy: RetryPolicy[F] = retry.retryPolicy
    implicit private def logOnErrorUnit[A]: (ClientErrOr[A], RetryDetails) => F[Unit] =
      (eitherErr, details) => console.printlnErr(s"influxDB client error '$eitherErr'. Retry details: '$details'")

    private def execute(req: Request[F]): F[ClientErrOr[Unit]] =
      client.fetch(req)(ClientError.errorOr[F, Unit](_.body.compile.drain.as(Right(())))).retryingM(successCondition)

    override def createDb: F[Unit] = {
      val req = Request[F](Method.POST, config.endpoint / "query").withEntity(UrlForm("q" -> config.dbCreationCommand))
      execute(req).flatMap {
        case Left(err) => console.printlnErr(err.show)
        case _         => F.unit
      }
    }

    override def write(point: InfluxPoint): F[ClientErrOr[Unit]] = {
      val uri = (config.endpoint / "write").withQueryParam("db", config.database)
      execute(Request[F](Method.POST, uri).withEntity(point))
    }

    override def query(ql: String): F[ClientErrOr[Json]] = {
      val uri = (config.endpoint / "query").withQueryParam("db", config.database)
      val req = Request[F](Method.POST, uri).withEntity(UrlForm("q" -> ql))
      val resp: F[ClientErrOr[Json]] = client.fetch(req)(ClientError.errorOr { r =>
        r.attemptAs[Json].value.map(_.leftMap(err => SerializationError(err.message, "Json")))
      })
      resp.retryingM(successCondition)
    }

    override def health: F[ClientErrOr[Json]] = {
      val req = Request[F](Method.GET, config.endpoint / "health")
      val resp: F[ClientErrOr[Json]] = client.fetch(req)(ClientError.errorOr { r =>
        r.attemptAs[Json].value.map(_.leftMap(err => SerializationError(err.message, "Json")))
      })
      resp.retryingM(successCondition)
    }
  }

  /**
    * Construct an instance of [[InfluxClient]].
    *
    * @param client  the underlying HTTP client.
    * @param console [[Console]] for logging.
    * @param config  InfluxDB config
    * @tparam F the effect type
    */
  final def apply[F[_]: Sync: Timer](
      client: Client[F],
      console: Console[F],
      config: AppConfig
  ): InfluxClient[F] = new LiveInfluxDbClient[F](client, console, config.influx, config.env)
}
