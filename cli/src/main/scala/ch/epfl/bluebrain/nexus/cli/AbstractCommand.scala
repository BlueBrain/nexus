package ch.epfl.bluebrain.nexus.cli

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliOpts.{envConfig, postgresConfig, token}
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.modules.config.ConfigModule
import ch.epfl.bluebrain.nexus.cli.modules.postgres.PostgresModule
import com.monovore.decline.Opts
import distage.{Injector, TagK}
import izumi.distage.model.Locator
import izumi.distage.model.definition.StandardAxis.Repo
import izumi.distage.model.definition.{Activation, ModuleDef}
import izumi.distage.model.plan.GCMode
import izumi.distage.model.recursive.LocatorRef

abstract class AbstractCommand[F[_]: TagK: Timer: ContextShift: Parallel](locatorOpt: Option[LocatorRef])(
    implicit F: ConcurrentEffect[F]
) {

  protected def locator: Opts[F[Locator]] = {
    locatorOpt match {
      case Some(value) => Opts(F.pure(value.get))
      case None =>
        (envConfig.orNone, postgresConfig.orNone, token.orNone).mapN {
          case (e, p, t) =>
            AppConfig.load[F](e, p, t).flatMap {
              case Left(err) => F.raiseError(err)
              case Right(value) =>
                val effects  = EffectModule[F]
                val cli      = CliModule[F]
                val config   = ConfigModule[F]
                val postgres = PostgresModule[F]
                val modules = effects ++ cli ++ config ++ postgres ++ new ModuleDef {
                  make[AppConfig].from(value)
                }
                Injector(Activation(Repo -> Repo.Prod)).produceF[F](modules, GCMode.NoGC).wrapRelease((_, _) => F.unit).use(loc => F.pure(loc))
//                Injector(Activation(Repo -> Repo.Prod)).produceF[F](modules, GCMode.NoGC).use(loc => F.pure(loc))
            }
        }
    }
  }
}
