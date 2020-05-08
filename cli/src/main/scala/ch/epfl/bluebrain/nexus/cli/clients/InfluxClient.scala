package ch.epfl.bluebrain.nexus.cli.clients

import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli._
import ch.epfl.bluebrain.nexus.cli.config.influx.InfluxConfig
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, EnvConfig}
import io.circe.Json
import org.http4s.client.Client
import org.http4s.{Method, Request, UrlForm}

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

  private class LiveInfluxDbClient[F[_]: Timer: Sync: Console](
      client: Client[F],
      config: InfluxConfig,
      env: EnvConfig
  ) extends AbstractHttpClient(client, env)
      with InfluxClient[F] {

    override def createDb: F[Unit] = {
      val req = Request[F](Method.POST, config.endpoint / "query").withEntity(UrlForm("q" -> config.dbCreationCommand))
      executeDiscard(req, returnValue = ()).flatMap {
        case Left(err) => console.printlnErr(err.show)
        case _         => F.unit
      }
    }

    override def write(point: InfluxPoint): F[ClientErrOr[Unit]] = {
      val uri = (config.endpoint / "write").withQueryParam("db", config.database)
      val req = Request[F](Method.POST, uri).withEntity(point)
      executeDiscard(req, returnValue = ())
    }

    override def query(ql: String): F[ClientErrOr[Json]] = {
      val uri = (config.endpoint / "query").withQueryParam("db", config.database)
      val req = Request[F](Method.POST, uri).withEntity(UrlForm("q" -> ql))
      executeParse[Json](req)
    }

    override def health: F[ClientErrOr[Json]] = {
      val req = Request[F](Method.GET, config.endpoint / "health")
      executeParse[Json](req)
    }
  }

  /**
    * Construct an instance of [[InfluxClient]].
    *
    * @param client  the underlying HTTP client
    * @param config  the application config
    * @param console [[Console]] for logging
    */
  final def apply[F[_]: Sync: Timer](
      client: Client[F],
      config: AppConfig,
      console: Console[F]
  ): InfluxClient[F] = {
    implicit val c: Console[F] = console
    new LiveInfluxDbClient[F](client, config.influx, config.env)
  }
}
