package ch.epfl.bluebrain.nexus.cli.dummies

import cats.Applicative
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.ClientStatusError
import ch.epfl.bluebrain.nexus.cli.ClientErrOr
import ch.epfl.bluebrain.nexus.cli.clients.ProjectClient
import ch.epfl.bluebrain.nexus.cli.sse.{OrgLabel, OrgUuid, ProjectLabel, ProjectUuid}
import org.http4s.Status

class TestProjectClient[F[_]](map: Map[(OrgUuid, ProjectUuid), (OrgLabel, ProjectLabel)])(implicit F: Applicative[F])
    extends ProjectClient[F] {

  override def labels(org: OrgUuid, proj: ProjectUuid): F[ClientErrOr[(OrgLabel, ProjectLabel)]] = {
    map.get((org, proj)) match {
      case Some(value) => F.pure(Right(value))
      case None        =>
        F.pure(
          Left(
            ClientStatusError(
              Status.NotFound,
              s"The project identified by organization '${org.show}' and '${proj.show}' was not found"
            )
          )
        )
    }
  }
}
