package ch.epfl.bluebrain.nexus.cli.dummies

import cats.effect.ConcurrentEffect
import ch.epfl.bluebrain.nexus.cli.Console
import ch.epfl.bluebrain.nexus.cli.clients.ProjectClient
import ch.epfl.bluebrain.nexus.cli.sse.OrgUuid.unsafe._
import ch.epfl.bluebrain.nexus.cli.sse.ProjectUuid.unsafe._
import distage.{ModuleDef, TagK}
import izumi.distage.model.definition.StandardAxis.Repo

final class TestCliModule[F[_]: ConcurrentEffect: TagK] extends ModuleDef {
  make[TestConsole[F]].tagged(Repo.Dummy).fromEffect(TestConsole[F])
  make[Console[F]].tagged(Repo.Dummy).from { tc: TestConsole[F] => tc }

  make[ProjectClient[F]]
    .tagged(Repo.Dummy)
    .from(
      new TestProjectClient[F](
        Map(
          (
            // matches the uuids from the events.json file used for testing
            ("e6a84231-5df7-41cf-9d18-286892d119ec", "d576d282-1049-4a0c-9240-ff34b5e879f2"),
            ("tutorialnexus", "datamodels")
          )
        )
      )
    )
}

object TestCliModule {

  final def apply[F[_]: ConcurrentEffect: TagK]: TestCliModule[F] =
    new TestCliModule[F]

}
