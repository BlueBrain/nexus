package ch.epfl.bluebrain.nexus.delta.service

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.cluster.typed.{Cluster, Join}
import cats.implicits._
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import com.typesafe.config.ConfigFactory
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import slick.jdbc.H2Profile.api._
import slick.jdbc.JdbcBackend
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.duration._

abstract class AbstractDBSpec(configName: String)
    extends ScalaTestWithActorTestKit(ConfigFactory.load(configName))
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers {

  implicit private val scheduler: Scheduler = Scheduler.global

  private var db: JdbcBackend.Database = null

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    Task
      .fromFuture {
        db = Database.forConfig("h2.db", system.settings.config)
        db.run {
          sqlu"""DROP TABLE IF EXISTS PUBLIC."journal";

             CREATE TABLE IF NOT EXISTS PUBLIC."journal"
             (
                 "ordering"        BIGINT AUTO_INCREMENT,
                 "persistence_id"  VARCHAR(255)               NOT NULL,
                 "sequence_number" BIGINT                     NOT NULL,
                 "deleted"         BOOLEAN      DEFAULT FALSE NOT NULL,
                 "tags"            VARCHAR(255) DEFAULT NULL,
                 "message"         BYTEA                      NOT NULL,
                 PRIMARY KEY ("persistence_id", "sequence_number")
             );

             CREATE UNIQUE INDEX "journal_ordering_idx" ON PUBLIC."journal" ("ordering");

             DROP TABLE IF EXISTS PUBLIC."snapshot";

             CREATE TABLE IF NOT EXISTS PUBLIC."snapshot"
             (
                 "persistence_id"  VARCHAR(255) NOT NULL,
                 "sequence_number" BIGINT       NOT NULL,
                 "created"         BIGINT       NOT NULL,
                 "snapshot"        BYTEA        NOT NULL,
                 PRIMARY KEY ("persistence_id", "sequence_number")
             );"""
        }
      }
      .as(())
      .runSyncUnsafe(30.seconds)
    val cluster = Cluster(system)
    cluster.manager ! Join(cluster.selfMember.address)
  }

  override protected def afterAll(): Unit = {
    db.close()

    super.afterAll()
  }

}
