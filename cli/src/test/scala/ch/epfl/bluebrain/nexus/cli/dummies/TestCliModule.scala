package ch.epfl.bluebrain.nexus.cli.dummies

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import ch.epfl.bluebrain.nexus.cli.clients.{EventStreamClient, ProjectClient, SparqlClient}
import ch.epfl.bluebrain.nexus.cli.modules.config.{Config, ConfigShow}
import ch.epfl.bluebrain.nexus.cli.modules.influx.{Influx, InfluxProjection}
import ch.epfl.bluebrain.nexus.cli.modules.postgres.{Postgres, PostgresProjection}
import ch.epfl.bluebrain.nexus.cli.sse.Event
import ch.epfl.bluebrain.nexus.cli.sse.OrgUuid.unsafe._
import ch.epfl.bluebrain.nexus.cli.sse.ProjectUuid.unsafe._
import ch.epfl.bluebrain.nexus.cli.{AbstractCommand, Cli, Console}
import com.monovore.decline.Opts
import distage.{ModuleDef, TagK}
import izumi.distage.model.definition.StandardAxis.Repo
import izumi.distage.model.providers.ProviderMagnet

final class TestCliModule[F[_]: Parallel: ContextShift: Timer: ConcurrentEffect: TagK](events: List[Event])
    extends ModuleDef {
  tag(Repo.Dummy)

  make[TestConsole[F]].fromEffect(TestConsole[F])
  make[Console[F]].from { tc: TestConsole[F] => tc }

  make[ProjectClient[F]].from {
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
  }

  make[SparqlClient[F]].fromEffect { TestSparqlClient[F](events) }

  make[EventStreamClient[F]].from { pc: ProjectClient[F] => new TestEventStreamClient[F](events, pc) }

  make[Cli[F]].from(new Cli[F](_, _))

  make[NonEmptyList[AbstractCommand[F]]].from {
    NonEmptyList
      .of(
        ProviderMagnet(Config[F](_)),
        ProviderMagnet(Postgres[F](_)),
        ProviderMagnet(Influx[F](_))
      )
      .map(_.map(NonEmptyList.one))
      .reduceLeft(_.map2(_)(_ ::: _))
  }

  make[Opts[Resource[F, ConfigShow[F]]]].from { Opts apply Resource.pure(_: ConfigShow[F]) }
  // stub Influx & Postgres launcher to avoid instantiating their dependencies
  make[Opts[Resource[F, InfluxProjection[F]]]].from(Opts(???))
  make[Opts[Resource[F, PostgresProjection[F]]]].from(Opts(???))
}

object TestCliModule {

  final def apply[F[_]: Parallel: ContextShift: Timer: ConcurrentEffect: TagK](events: List[Event]): TestCliModule[F] =
    new TestCliModule[F](events)

}
