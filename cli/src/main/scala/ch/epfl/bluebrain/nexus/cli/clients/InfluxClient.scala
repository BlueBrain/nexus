package ch.epfl.bluebrain.nexus.cli.clients

import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.{SerializationError, Unexpected}
import ch.epfl.bluebrain.nexus.cli._
import ch.epfl.bluebrain.nexus.cli.config.influx.InfluxConfig
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, EnvConfig}
import io.circe.Json
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.{Method, Request, Response, UrlForm}
import retry.CatsEffect._
import retry.RetryPolicy
import retry.syntax.all._

import scala.util.control.NonFatal

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
      config: InfluxConfig,
      env: EnvConfig
  )(implicit val F: Sync[F], console: Console[F])
      extends InfluxClient[F] {

    private val retry                                = env.httpClient.retry
    private def successCondition[A]                  = retry.condition.notRetryFromEither[A] _
    implicit private val retryPolicy: RetryPolicy[F] = retry.retryPolicy
    implicit private def logOnError[A]               = logRetryErrors[F, A]("interacting with the influxDB API")

    private def executeDrain(req: Request[F]): F[ClientErrOr[Unit]] =
      execute(req, _.body.compile.drain.as(Right(())))

    private def executeParseJson(req: Request[F]): F[ClientErrOr[Json]] =
      execute(req, _.attemptAs[Json].value.map(_.leftMap(err => SerializationError(err.message, "Json"))))

    private def execute[A](req: Request[F], f: Response[F] => F[ClientErrOr[A]]): F[ClientErrOr[A]] =
      client
        .fetch(req)(ClientError.errorOr[F, A](r => f(r)))
        .recoverWith {
          case NonFatal(err) => F.delay(Left(Unexpected(err.getMessage.take(30))))
        }
        .retryingM(successCondition[A])

    override def createDb: F[Unit] = {
      val req = Request[F](Method.POST, config.endpoint / "query").withEntity(UrlForm("q" -> config.dbCreationCommand))
      executeDrain(req).flatMap {
        case Left(err) => console.printlnErr(err.show)
        case _         => F.unit
      }
    }

    override def write(point: InfluxPoint): F[ClientErrOr[Unit]] = {
      val uri = (config.endpoint / "write").withQueryParam("db", config.database)
      executeDrain(Request[F](Method.POST, uri).withEntity(point))
    }

    override def query(ql: String): F[ClientErrOr[Json]] = {
      val uri = (config.endpoint / "query").withQueryParam("db", config.database)
      val req = Request[F](Method.POST, uri).withEntity(UrlForm("q" -> ql))
      executeParseJson(req)
    }

    override def health: F[ClientErrOr[Json]] = {
      val req = Request[F](Method.GET, config.endpoint / "health")
      executeParseJson(req)
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
  ): InfluxClient[F] = {
    implicit val c: Console[F] = console
    new LiveInfluxDbClient[F](client, config.influx, config.env)
  }
}
