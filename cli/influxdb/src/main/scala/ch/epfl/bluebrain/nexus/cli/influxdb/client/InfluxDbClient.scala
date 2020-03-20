package ch.epfl.bluebrain.nexus.cli.influxdb.client

import cats.effect.concurrent.Ref
import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.error.ClientError
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig.InfluxDbClientConfig
import ch.epfl.bluebrain.nexus.cli.{ClientErrOr, _}
import org.http4s.client.Client
import org.http4s.{Method, Request, Status, UrlForm}
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

  final private[cli] class LiveInfluxDbClient[F[_]: Timer](
      client: Client[F],
      console: Console[F],
      config: InfluxDbClientConfig
  )(implicit val F: Sync[F])
      extends InfluxDbClient[F] {
    implicit private val successCondition            = config.retry.retryCondition.notRetryFromEither[Unit] _
    implicit private val retryPolicy: RetryPolicy[F] = config.retry.retryPolicy
    implicit private val logOnErrorUnit: (ClientErrOr[Unit], RetryDetails) => F[Unit] =
      (eitherErr, details) => console.printlnErr(s"Client error '$eitherErr'. Retry details: '$details'")

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
        .flatMap {
          case Left(err) =>
            console.printlnErr(err.show)
          case _ =>
            F.unit
        }
    }

    override def write(database: String, point: Point): F[ClientErrOr[Unit]] = {
      val req = Request[F](
        method = Method.POST,
        uri = (config.endpoint / "write").withQueryParam("db", database)
      ).withEntity(point)
      performRequest(req)
    }

  }

  /**
    * Construct an instance of [[InfluxDbClient]].
    *
    * @param client  the underlying HTTP client.
    * @param console [[Console]] for logging.
    * @param config  InfluxDB config
    * @tparam F the effect type
    */
  final def apply[F[_]: Sync: Timer](
      client: Client[F],
      console: Console[F],
      config: InfluxDbClientConfig
  ): InfluxDbClient[F] = new LiveInfluxDbClient[F](client, console, config)

  final private[cli] class TestInfluxDbClient[F[_]](
      databases: Ref[F, Set[String]],
      points: Ref[F, Map[String, Vector[Point]]]
  )(implicit F: Sync[F])
      extends InfluxDbClient[F] {

    private def writePointIfDbExists(dbs: Set[String], database: String, point: Point): F[ClientErrOr[Unit]] = {
      if (dbs(database)) {
        points
          .update(_.updatedWith(database) {
            case None          => Some(Vector(point))
            case Some(current) => Some(current :+ point)
          })
          .as(Right(()))
      } else
        F.pure(Left(ClientError.ClientStatusError(Status.NotFound, s"Database '$database' doesn't exists")))

    }
    override def createDb(name: String): F[Unit] =
      databases.update(_ + name)

    override def write(database: String, point: Point): F[ClientErrOr[Unit]] =
      for {
        dbs <- databases.get
        res <- writePointIfDbExists(dbs, database, point)
      } yield res

  }
}
