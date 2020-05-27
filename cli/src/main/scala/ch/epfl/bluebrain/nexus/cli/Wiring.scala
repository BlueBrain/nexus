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
import distage.{Injector, Tag, TagK}
import izumi.distage.model.Locator
import izumi.distage.model.definition.StandardAxis.Repo
import izumi.distage.model.definition.{Activation, Module, ModuleDef}
import izumi.distage.model.plan.GCMode
import izumi.distage.model.reflection.DIKey

object Wiring {

  def wireOne[F[_]: TagK: Timer: ContextShift: Parallel, A: Tag](
      implicit F: ConcurrentEffect[F]
  ): Opts[Resource[F, A]] =
    wireLocator[F](GCMode(Set(DIKey.get[A]))).map(_.map(_.get[A]))

  private def wireLocator[F[_]: TagK: Timer: ContextShift: Parallel](gcMode: GCMode)(
      implicit F: ConcurrentEffect[F]
  ): Opts[Resource[F, Locator]] =
    (envConfig.orNone, postgresConfig.orNone, influxConfig.orNone, token.orNone).mapN {
      case (e, p, i, t) =>
        val res: Resource[F, Module] = Resource.liftF {
          AppConfig.load[F](e, p, i, t).flatMap[Module] {
            case Left(err) => F.raiseError(err)
            case Right(value) =>
              val effects  = EffectModule[F]
              val cli      = CliModule[F]
              val config   = ConfigModule[F]
              val postgres = PostgresModule[F]
              val influx   = InfluxModule[F]
              val appCfg   = new ModuleDef { make[AppConfig].fromValue(value) }
              val modules  = effects ++ cli ++ config ++ postgres ++ influx ++ appCfg
              F.pure(modules)
          }
        }

        res.flatMap { modules => Injector(Activation(Repo -> Repo.Prod)).produceF(modules, gcMode).toCats }
    }

}
