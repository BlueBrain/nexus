package ch.epfl.bluebrain.nexus.cli.influxdb.modules

import cats.effect.concurrent.Ref
import cats.effect.{Async, Bracket, Concurrent, ConcurrentEffect, ContextShift, Effect, Sync, Timer}
import ch.epfl.bluebrain.nexus.cli.Console.LiveConsole
import ch.epfl.bluebrain.nexus.cli.EventStreamClient.LiveEventStreamClient
import ch.epfl.bluebrain.nexus.cli.ProjectClient.{LiveProjectClient, UUIDToLabel}
import ch.epfl.bluebrain.nexus.cli.SparqlClient.LiveSparqlClient
import ch.epfl.bluebrain.nexus.cli.influxdb.InfluxDbIndexer
import ch.epfl.bluebrain.nexus.cli.influxdb.client.InfluxDbClient
import ch.epfl.bluebrain.nexus.cli.influxdb.client.InfluxDbClient.LiveInfluxDbClient
import ch.epfl.bluebrain.nexus.cli.{
  Console,
  EventStreamClient,
  ProjectClient,
  ProjectLabelRef,
  ProjectUuidRef,
  SparqlClient
}
import distage.TagK
import izumi.distage.model.definition.ModuleDef
import izumi.distage.model.definition.StandardAxis.Repo
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext.global

class InfluxDbModule[F[_]: ConcurrentEffect: Concurrent: ContextShift: Timer: TagK] extends ModuleDef {

  addImplicit[ConcurrentEffect[F]]
  addImplicit[Concurrent[F]]
  addImplicit[Effect[F]]
  addImplicit[Async[F]]
  addImplicit[Sync[F]]
  addImplicit[Bracket[F, Throwable]]
  addImplicit[Timer[F]]
  addImplicit[ContextShift[F]]

  make[Console[F]].tagged(Repo.Prod).from[LiveConsole[F]]
  make[Ref[F, UUIDToLabel]].tagged(Repo.Prod).fromEffect(Ref[F].of(Map.empty[ProjectUuidRef, ProjectLabelRef]))
  make[Client[F]].tagged(Repo.Prod).fromResource(BlazeClientBuilder[F](global).resource)
  make[ProjectClient[F]].tagged(Repo.Prod).from[LiveProjectClient[F]]
  make[EventStreamClient[F]].tagged(Repo.Prod).from[LiveEventStreamClient[F]]
  make[SparqlClient[F]].tagged(Repo.Prod).from[LiveSparqlClient[F]]
  make[InfluxDbClient[F]].tagged(Repo.Prod).from[LiveInfluxDbClient[F]]
  make[InfluxDbIndexer[F]].tagged(Repo.Prod).from[InfluxDbIndexer[F]]

}

object InfluxDbModule {

  def apply[F[_]: ConcurrentEffect: Concurrent: ContextShift: Timer: TagK]: InfluxDbModule[F] =
    new InfluxDbModule[F]
}
