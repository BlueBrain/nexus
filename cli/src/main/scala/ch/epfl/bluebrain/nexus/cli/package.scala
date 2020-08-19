package ch.epfl.bluebrain.nexus

import cats.Applicative
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.sse.{Event, Offset, OrgLabel, ProjectLabel}
import retry.RetryDetails
import retry.RetryDetails.{GivingUp, WillDelayAndRetry}

package object cli {

  type ClientErrOr[A]       = Either[ClientError, A]
  type CliErrOr[A]          = Either[CliError, A]
  type ClientErrOffsetOr[A] = Either[(Offset, ClientError), A]
  type CliErrOffsetOr[A]    = Either[(Offset, CliError), A]
  type LabeledEvent         = (Event, Offset, OrgLabel, ProjectLabel)

  def logRetryErrors[F[_], A](
      action: String
  )(implicit console: Console[F], F: Applicative[F]): (ClientErrOr[A], RetryDetails) => F[Unit] = {
    case (Left(err), WillDelayAndRetry(nextDelay, retriesSoFar, _)) =>
      console.println(s"""Error occurred while $action:
                         |
                         |${err.asString}
                         |
                         |Will retry in ${nextDelay.toMillis}ms ... (retries so far: $retriesSoFar)""".stripMargin)
    case (Left(err), GivingUp(totalRetries, _))                     =>
      console.println(s"""Error occurred while $action:
                         |
                         |${err.asString}
                         |
                         |Giving up ... (total retries: $totalRetries)""".stripMargin)
    case _                                                          => F.unit

  }

}
