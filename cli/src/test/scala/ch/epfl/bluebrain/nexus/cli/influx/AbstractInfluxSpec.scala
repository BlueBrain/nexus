package ch.epfl.bluebrain.nexus.cli.influx

import cats.effect.{Blocker, IO, Resource}
import ch.epfl.bluebrain.nexus.cli.{AbstractCliSpec, Console}
import ch.epfl.bluebrain.nexus.cli.clients.InfluxClient
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.influx.InfluxDocker.InfluxHostConfig
import izumi.distage.model.definition.{Module, ModuleDef}
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.duration._

class AbstractInfluxSpec extends AbstractCliSpec {

  override protected def defaultModules: Module = {
    super.defaultModules ++ new InfluxDocker.Module[IO]
  }

  override def testModule: ModuleDef =
    new ModuleDef {
      make[AppConfig].fromEffect { host: InfluxHostConfig =>
        copyConfigs.flatMap { case (envFile, _, influxFile) =>
          AppConfig.load[IO](Some(envFile), influxConfigFile = Some(influxFile)).flatMap {
            case Left(value)  => IO.raiseError(value)
            case Right(value) =>
              val influxOffsetFile = influxFile.getParent.resolve("influx.offset")
              val cfg              = value.copy(influx =
                value.influx.copy(
                  endpoint = host.endpoint,
                  offsetFile = influxOffsetFile,
                  offsetSaveInterval = 100.milliseconds
                )
              )
              IO.pure(cfg)
          }
        }
      }
      make[InfluxClient[IO]].fromResource {
        (_: InfluxDocker.Container, cfg: AppConfig, blocker: Blocker, console: Console[IO]) =>
          BlazeClientBuilder[IO](blocker.blockingContext).resource.flatMap { client =>
            val influxClient = InfluxClient(client, cfg, console)
            waitForInfluxReady(influxClient).map(_ => influxClient)
          }
      }
    }

  private def waitForInfluxReady(
      client: InfluxClient[IO],
      maxDelay: FiniteDuration = 90.seconds
  ): Resource[IO, Unit] = {
    import retry.CatsEffect._
    import retry.RetryPolicies._
    import retry._
    val policy   = limitRetriesByCumulativeDelay[IO](maxDelay, constantDelay(5.second))
    val healthIO = retryingOnAllErrors(
      policy = policy,
      onError = (_: Throwable, _) => IO.delay(println("Influx Container not ready, retrying..."))
    ) {
      client.health.map {
        case Left(err) => throw err
        case Right(_)  => ()
      }
    }
    Resource.liftF(healthIO)
  }
}
