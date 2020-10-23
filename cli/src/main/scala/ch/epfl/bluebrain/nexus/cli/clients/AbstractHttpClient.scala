package ch.epfl.bluebrain.nexus.cli.clients

import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.{SerializationError, Unexpected}
import ch.epfl.bluebrain.nexus.cli.config.EnvConfig
import ch.epfl.bluebrain.nexus.cli.{logRetryErrors, ClientErrOr, Console}
import io.circe.Decoder
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.{Request, Response}
import retry.CatsEffect._
import retry.RetryPolicy
import retry.syntax.all._

import scala.reflect.ClassTag
import scala.util.control.NonFatal

class AbstractHttpClient[F[_]: Timer](client: Client[F], env: EnvConfig)(implicit
    protected val F: Sync[F],
    protected val console: Console[F]
) {

  protected val retry                                = env.httpClient.retry
  protected def successCondition[A]                  = retry.condition.notRetryFromEither[A] _
  implicit protected val retryPolicy: RetryPolicy[F] = retry.retryPolicy
  implicit protected def logOnError[A]               = logRetryErrors[F, A]("interacting with an HTTP API")

  protected def executeDiscard[A](req: Request[F], returnValue: => A): F[ClientErrOr[A]] =
    execute(req, _.body.compile.drain.as(Right(returnValue)))

  protected def executeParse[A: Decoder](req: Request[F])(implicit A: ClassTag[A]): F[ClientErrOr[A]] =
    execute(
      req,
      _.attemptAs[A].value.map(
        _.leftMap(err =>
          SerializationError(err.message, s"The response payload was not of type '${A.runtimeClass.getSimpleName}'")
        )
      )
    )

  private def execute[A](req: Request[F], f: Response[F] => F[ClientErrOr[A]]): F[ClientErrOr[A]] =
    client
      .run(req)
      .use(ClientError.errorOr[F, A](r => f(r)))
      .recoverWith { case NonFatal(err) =>
        F.delay(Left(Unexpected(Option(err.getMessage).getOrElse("").take(30))))
      }
      .retryingM(successCondition[A])
}
