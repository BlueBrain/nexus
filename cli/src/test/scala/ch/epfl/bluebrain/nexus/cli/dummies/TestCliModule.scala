package ch.epfl.bluebrain.nexus.cli.dummies

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import ch.epfl.bluebrain.nexus.cli.clients.{EventStreamClient, ProjectClient, SparqlClient}
import ch.epfl.bluebrain.nexus.cli.sse.Event
import ch.epfl.bluebrain.nexus.cli.sse.OrgUuid.unsafe._
import ch.epfl.bluebrain.nexus.cli.sse.ProjectUuid.unsafe._
import ch.epfl.bluebrain.nexus.cli.{Cli, Console}
import distage.{ModuleDef, TagK}
import izumi.distage.model.definition.StandardAxis.Repo
import izumi.distage.model.recursive.LocatorRef

final class TestCliModule[F[_]: Parallel: ContextShift: Timer: ConcurrentEffect: TagK](events: List[Event])
    extends ModuleDef {
  make[TestConsole[F]].tagged(Repo.Dummy).fromEffect(TestConsole[F])
  make[Console[F]].tagged(Repo.Dummy).from { tc: TestConsole[F] => tc }

  make[ProjectClient[F]]
    .tagged(Repo.Dummy)
    .from(
      new TestProjectClient[F](
        // matches the uuids from the events.json file used for testing
        Map(
          (
            ("e6a84231-5df7-41cf-9d18-286892d119ec", "d576d282-1049-4a0c-9240-ff34b5e879f2"),
            ("tutorialnexus", "datamodels")
          ),
          (
            ("a605b71a-377d-4df3-95f8-923149d04106", "a7d69059-8d1d-4dac-800f-90b6b6ab94ee"),
            ("bbp", "atlas")
          )
        )
      )
    )

  make[SparqlClient[F]].tagged(Repo.Dummy).fromEffect { TestSparqlClient[F](events) }

  make[EventStreamClient[F]].tagged(Repo.Dummy).from { pc: ProjectClient[F] =>
    new TestEventStreamClient[F](events, pc)
  }

  make[Cli[F]].tagged(Repo.Dummy).from { locatorRef: LocatorRef => new Cli[F](Some(locatorRef)) }
}

object TestCliModule {

  final def apply[F[_]: Parallel: ContextShift: Timer: ConcurrentEffect: TagK](events: List[Event]): TestCliModule[F] =
    new TestCliModule[F](events)

}
