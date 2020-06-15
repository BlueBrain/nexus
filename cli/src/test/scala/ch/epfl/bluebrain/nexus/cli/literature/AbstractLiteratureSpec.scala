//package ch.epfl.bluebrain.nexus.cli.literature
//
//import cats.effect.{Blocker, IO, Resource}
//import ch.epfl.bluebrain.nexus.cli.clients.{ElasticSearchClient, InfluxClient}
//import ch.epfl.bluebrain.nexus.cli.config.AppConfig
//import ch.epfl.bluebrain.nexus.cli.config.literature.ElasticSearchLiteratureConfig
//import ch.epfl.bluebrain.nexus.cli.influx.InfluxDocker.InfluxHostConfig
//import ch.epfl.bluebrain.nexus.cli.literature.ElasticSearchDocker.ElasticSearchHostConfig
//import ch.epfl.bluebrain.nexus.cli.{AbstractCliSpec, Console}
//import izumi.distage.model.definition.{Module, ModuleDef}
//import org.http4s.client.blaze.BlazeClientBuilder
//
//import scala.concurrent.duration._
//
//class AbstractLiteratureSpec extends AbstractCliSpec {
//
//  override protected def defaultModules: Module = {
//    super.defaultModules ++ new ElasticSearchDocker.Module[IO]
//  }
//
//  override def testModule: ModuleDef = new ModuleDef {
//    make[AppConfig].fromEffect { host: ElasticSearchHostConfig =>
//      copyConfigs.flatMap {
//        case (envFile, _, _, literatureFile) =>
//          AppConfig.load[IO](Some(envFile), influxConfigFile = Some(literatureFile)).flatMap {
//            case Left(value) => IO.raiseError(value)
//            case Right(value) =>
//              val literatureOffsetFile = literatureFile.getParent.resolve("literature.offset")
//              val cfg = value.copy(literature =
//                value.literature.copy(
//                  elasticSearch = value.literature.elasticSearch.copy(endpoint = host.endpoint),
//                  offsetFile = literatureOffsetFile,
//                  offsetSaveInterval = 100.milliseconds
//                )
//              )
//              IO.pure(cfg)
//          }
//      }
//    }
//    make[ElasticSearchClient[IO]].fromResource {
//      (_: ElasticSearchDocker.Container, cfg: AppConfig, blocker: Blocker, console: Console[IO]) =>
//        BlazeClientBuilder[IO](blocker.blockingContext).resource.flatMap { client =>
//          val influxClient = InfluxClient(client, cfg, console)
//          waitForPostgresReady(influxClient).map(_ => influxClient)
//        }
//    }
//  }
//
//  private def waitForPostgresReady(
//      client: InfluxClient[IO],
//      maxDelay: FiniteDuration = 90.seconds
//  ): Resource[IO, Unit] = {
//    import retry.CatsEffect._
//    import retry.RetryPolicies._
//    import retry._
//    val policy = limitRetriesByCumulativeDelay[IO](maxDelay, constantDelay(5.second))
//    val healthIO = retryingOnAllErrors(
//      policy = policy,
//      onError = (_: Throwable, _) => IO.delay(println("Influx Container not ready, retrying..."))
//    ) {
//      client.health.map {
//        case Left(err) => throw err
//        case Right(_)  => ()
//      }
//    }
//    Resource.liftF(healthIO)
//  }
//}
