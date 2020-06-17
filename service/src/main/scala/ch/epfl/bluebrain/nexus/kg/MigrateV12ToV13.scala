package ch.epfl.bluebrain.nexus.kg

import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope, NoOffset, PersistenceQuery}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.AdminClient
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.test.Resources
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.resources.Event.{Created, Updated}
import ch.epfl.bluebrain.nexus.kg.resources.{OrganizationRef, ResId, Views}
import com.typesafe.scalalogging.Logger
import io.circe.Json
import io.circe.parser.parse
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock

import scala.concurrent.Future

object MigrateV12ToV13 extends Resources {
  private val log                                     = Logger[MigrateV12ToV13.type]
  private val newMapping                              = jsonContentOf("/elasticsearch/mapping.json")
  private val defaultEsId                             = nxv.defaultElasticSearchIndex.value
  implicit private val mockedAcls: AccessControlLists = AccessControlLists.empty

  def migrate(
      views: Views[Task],
      adminClient: AdminClient[Task]
  )(implicit config: AppConfig, as: ActorSystem, sc: Scheduler, pm: CanBlock): Unit = {

    implicit val token: Option[AuthToken] = config.iam.serviceAccountToken

    def checkAndUpdateMapping(id: ResId, rev: Long, source: Json)(implicit
        project: Project,
        caller: Caller
    ): Task[Unit] = {

      source.hcursor.get[String]("mapping").flatMap(parse) match {
        case Left(err)                               =>
          log.error(s"Error while fetching mapping for view id ${id.show}. Reason: '$err'")
          Task.unit
        case Right(mapping) if mapping == newMapping =>
          Task.unit
        case _                                       =>
          views.update(id, rev, source deepMerge Json.obj("mapping" -> newMapping)).value.flatMap {
            case Left(err) =>
              log.error(s"Error updating the view with id '${id.show}' and rev '$rev'. Reason: '$err'")
              Task.unit
            case _         =>
              log.info(s"View with id '${id.show}' and rev '$rev' was successfully updated.")
              Task.unit
          }
      }
    }

    def fetchProject(orgRef: OrganizationRef, id: ResId)(f: Project => Task[Unit]): Task[Unit] = {
      adminClient.fetchProject(orgRef.id, id.parent.id).flatMap {
        case Some(project) => f(project)
        case None          =>
          log.error(s"Project with id '${id.parent.id}' was not found for view with id '${id.show}'")
          Task.unit

      }
    }

    log.info("Migrating views mappings.")
    val pq = PersistenceQuery(as).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    Task
      .fromFuture {
        pq.currentEventsByTag(s"type=${nxv.ElasticSearchView.value.asString}", NoOffset)
          .mapAsync(1) {
            case EventEnvelope(_, _, _, Created(id, orgRef, _, _, source, _, subject)) if id.value == defaultEsId   =>
              fetchProject(orgRef, id) { project =>
                checkAndUpdateMapping(id, 1L, source)(project, Caller(subject, Set(subject)))
              }.runToFuture
            case EventEnvelope(_, _, _, Updated(id, orgRef, rev, _, source, _, subject)) if id.value == defaultEsId =>
              fetchProject(orgRef, id) { project =>
                checkAndUpdateMapping(id, rev, source)(project, Caller(subject, Set(subject)))
              }.runToFuture
            case _                                                                                                  =>
              Future.unit

          }
          .runFold(0) {
            case (acc, _) =>
              if (acc % 10 == 0) log.info(s"Processed '$acc' persistence ids.")
              acc + 1
          }
          .map(_ => ())
      }
      .runSyncUnsafe()
    log.info("Finished migrating views mappings.")
  }

}
