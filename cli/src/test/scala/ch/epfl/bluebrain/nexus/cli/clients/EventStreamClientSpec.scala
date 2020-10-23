package ch.epfl.bluebrain.nexus.cli.clients

import java.util.UUID

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.AbstractCliSpec
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, EnvConfig}
import ch.epfl.bluebrain.nexus.cli.sse._
import ch.epfl.bluebrain.nexus.cli.utils.Http4sExtras
import fs2.Stream
import fs2.text.utf8Encode
import izumi.distage.model.definition.ModuleDef
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.{HttpApp, Response, Status}
import org.scalatest.OptionValues

class EventStreamClientSpec extends AbstractCliSpec with Http4sExtras with OptionValues {

  private val offset = Some(Offset(new UUID(1584614086454L, 0))) // 3rd event

  private def streamFor(offset: Option[Offset]): Stream[IO, Byte] = {
    val stream = Stream
      .fromIterator[IO](events.iterator)
      .map { ev =>
        val uuid = new UUID(ev.instant.toEpochMilli, 0)
        (ev, uuid)
      }
      .dropWhile { case (_, uuid) =>
        offset match {
          case Some(value) => uuid.getMostSignificantBits <= value.value.getMostSignificantBits
          case None        => false
        }
      }
      .map { case (ev, uuid) =>
        s"""data: ${ev.raw.noSpaces}
             |event: ${ev.eventType.getClass.getSimpleName}
             |id: $uuid
             |
             |""".stripMargin
      }
    stream.through(utf8Encode)
  }

  override def overrides: ModuleDef =
    new ModuleDef {
      include(defaultModules)
      make[Client[IO]].from { cfg: AppConfig =>
        val token   = cfg.env.token
        val httpApp = HttpApp[IO] {
          // global events with offset
          case GET -> `v1` / "resources" / "events" optbearer `token` lastEventId offset                           =>
            Response[IO](Status.Ok).withEntity(streamFor(Some(offset))).pure[IO]
          // global events
          case GET -> `v1` / "resources" / "events" optbearer `token`                                              =>
            Response[IO](Status.Ok).withEntity(streamFor(None)).pure[IO]
          // org events with offset
          case GET -> `v1` / "resources" / OrgLabelVar(`orgLabel`) / "events" optbearer `token` lastEventId offset =>
            Response[IO](Status.Ok).withEntity(streamFor(Some(offset))).pure[IO]
          // org events
          case GET -> `v1` / "resources" / OrgLabelVar(`orgLabel`) / "events" optbearer `token`                    =>
            Response[IO](Status.Ok).withEntity(streamFor(None)).pure[IO]
          // project events with offset
          case GET -> `v1` / "resources" / OrgLabelVar(`orgLabel`) / ProjectLabelVar(
                `projectLabel`
              ) / "events" optbearer `token` lastEventId offset =>
            Response[IO](Status.Ok).withEntity(streamFor(Some(offset))).pure[IO]
          // project events
          case GET -> `v1` / "resources" / OrgLabelVar(`orgLabel`) / ProjectLabelVar(
                `projectLabel`
              ) / "events" optbearer `token` =>
            Response[IO](Status.Ok).withEntity(streamFor(None)).pure[IO]
        }
        Client.fromHttpApp(httpApp)
      }
    }

  "An EventStreamClient" should {
    "return all events" in { (client: Client[IO], pc: ProjectClient[IO], env: EnvConfig) =>
      val ec = EventStreamClient[IO](client, pc, env)
      for {
        eventStream <- ec.apply(None)
        stream      <- eventStream.value
        eventList   <- stream.collect { case Right((event, _, _)) => event }.compile.toList
        _            = eventList shouldEqual events
      } yield ()
    }
    "return all events from offset" in { (client: Client[IO], pc: ProjectClient[IO], env: EnvConfig) =>
      val ec = EventStreamClient[IO](client, pc, env)
      for {
        eventStream <- ec.apply(offset)
        stream      <- eventStream.value
        eventList   <- stream.collect { case Right((event, _, _)) => event }.compile.toList
        _            = eventList shouldEqual events.drop(3)
      } yield ()
    }
    "return all org events" in { (client: Client[IO], pc: ProjectClient[IO], env: EnvConfig) =>
      val ec = EventStreamClient[IO](client, pc, env)
      for {
        eventStream <- ec.apply(orgLabel, None)
        stream      <- eventStream.value
        eventList   <- stream.collect { case Right((event, _, _)) => event }.compile.toList
        _            = eventList shouldEqual events
      } yield ()
    }
    "return all org events from offset" in { (client: Client[IO], pc: ProjectClient[IO], env: EnvConfig) =>
      val ec = EventStreamClient[IO](client, pc, env)
      for {
        eventStream <- ec.apply(orgLabel, offset)
        stream      <- eventStream.value
        eventList   <- stream.collect { case Right((event, _, _)) => event }.compile.toList
        _            = eventList shouldEqual events.drop(3)
      } yield ()
    }
    "return all proj events" in { (client: Client[IO], pc: ProjectClient[IO], env: EnvConfig) =>
      val ec = EventStreamClient[IO](client, pc, env)
      for {
        eventStream <- ec.apply(orgLabel, projectLabel, None)
        stream      <- eventStream.value
        eventList   <- stream.collect { case Right((event, _, _)) => event }.compile.toList
        _            = eventList shouldEqual events
      } yield ()
    }
    "return all proj events from offset" in { (client: Client[IO], pc: ProjectClient[IO], env: EnvConfig) =>
      val ec = EventStreamClient[IO](client, pc, env)
      for {
        eventStream <- ec.apply(orgLabel, projectLabel, offset)
        stream      <- eventStream.value
        eventList   <- stream.collect { case Right((event, _, _)) => event }.compile.toList
        _            = eventList shouldEqual events.drop(3)
      } yield ()
    }
  }
}
