package ch.epfl.bluebrain.nexus.cli

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliOpts._
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.modules.config.ConfigModule
import ch.epfl.bluebrain.nexus.cli.modules.influx.InfluxModule
import ch.epfl.bluebrain.nexus.cli.modules.postgres.PostgresModule
import com.monovore.decline.Opts
import distage.{Injector, TagK}
import izumi.distage.model.Locator
import izumi.distage.model.definition.StandardAxis.Repo
import izumi.distage.model.definition.{Activation, Module, ModuleDef}
import izumi.distage.model.plan.Roots
import izumi.distage.model.recursive.LocatorRef

abstract class AbstractCommand[F[_]: TagK: Timer: ContextShift: Parallel](locatorOpt: Option[LocatorRef])(implicit
    F: ConcurrentEffect[F]
) {

  protected def locatorResource: Opts[Resource[F, Locator]] =
    locatorOpt match {
      case Some(value) => Opts(Resource.make(F.delay(value.get))(_ => F.unit))
      case None        =>
        (envConfig.orNone, postgresConfig.orNone, influxConfig.orNone, token.orNone).mapN { case (e, p, i, t) =>
          val res: Resource[F, Module] = Resource.make({
            AppConfig.load[F](e, p, i, t).flatMap[Module] {
              case Left(err)    => F.raiseError(err)
              case Right(value) =>
                val effects  = EffectModule[F]
                val cli      = CliModule[F]
                val config   = ConfigModule[F]
                val postgres = PostgresModule[F]
                val influx   = InfluxModule[F]
                val modules  = effects ++ cli ++ config ++ postgres ++ influx ++ new ModuleDef {
                  make[AppConfig].from(value)
                }
                F.pure(modules)
            }
          })(_ => F.unit)

          res.flatMap { modules =>
            Injector(Activation(Repo -> Repo.Prod)).produceF[F](modules, Roots.Everything).toCats
          }
        }
    }
}
