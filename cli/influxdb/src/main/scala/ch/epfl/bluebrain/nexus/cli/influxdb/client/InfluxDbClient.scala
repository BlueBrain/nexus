package ch.epfl.bluebrain.nexus.cli.influxdb.client

import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig.InfluxDbClientConfig
import ch.epfl.bluebrain.nexus.cli.{ClientErrOr, _}
import io.chrisdavenport.log4cats.Logger
import org.http4s.client.Client
import org.http4s.{Method, Request, UrlForm}
import retry.CatsEffect._
import retry.syntax.all._
import retry.{RetryDetails, RetryPolicy}

trait InfluxDbClient[F[_]] {

  /**
    * Create a new InfluxDb database.
    * @param name  the database name
    */
  def createDb(name: String): F[Unit]

  /**
    * Write an InfluxDB point to a database.
    * @param database  the database to write to.
    * @param point     the point to write
    * @return
    */
  def write(database: String, point: Point): F[ClientErrOr[Unit]]

}

object InfluxDbClient {

  /**
    * Construct an instance of [[InfluxDbClient]].
    *
    * @param client  the underlying HTTP client.
    * @param config  InfluxDB config
    * @tparam F the effect type
    */
  final def apply[F[_]](
      client: Client[F],
      config: InfluxDbClientConfig
  )(implicit F: Sync[F], T: Timer[F]): InfluxDbClient[F] = new InfluxDbClient[F] {

    private implicit val successCondition            = config.retry.retryCondition.notRetryFromEither[Unit] _
    private implicit val retryPolicy: RetryPolicy[F] = config.retry.retryPolicy
    private implicit val logOnErrorUnit: (ClientErrOr[Unit], RetryDetails) => F[Unit] =
      (eitherErr, details) => Logger[F].error(s"Client error '$eitherErr'. Retry details: '$details'")

    private def performRequest(req: Request[F]): F[ClientErrOr[Unit]] =
      client
        .fetch(req)(
          ClientError.errorOr[F, Unit](
            _.body.compile.drain.as(Right(()))
          )
        )
        .retryingM(successCondition)

    override def createDb(name: String): F[Unit] = {
      val req = Request[F](method = Method.POST, uri = config.endpoint / "query").withEntity(
        UrlForm(
          "q" -> s"""CREATE DATABASE "$name" WITH DURATION ${config.duration} REPLICATION ${config.replication} SHARD DURATION 1h NAME "$name""""
        )
      )
      performRequest(req)
        .as(())

    }

    override def write(database: String, point: Point): F[ClientErrOr[Unit]] = {
      val req = Request[F](
        method = Method.POST,
        uri = (config.endpoint / "write").withQueryParam("db", database)
      ).withEntity(point)
      performRequest(req)
    }
  }
}
