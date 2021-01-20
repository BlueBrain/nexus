package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.actor.ActorSystem
import akka.actor.typed
import akka.cluster.typed.{Cluster, Join}
import akka.testkit.TestKit
import cats.implicits._
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import com.typesafe.config.{Config, ConfigFactory}
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, CancelAfterFailure}
import slick.jdbc.H2Profile.api._
import slick.jdbc.JdbcBackend
import slick.jdbc.JdbcBackend.Database

import java.util.UUID
import scala.concurrent.duration._
import akka.actor.typed.scaladsl.adapter._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.AbstractDBSpec.config

import java.io.File
import scala.reflect.io.Directory

abstract class AbstractDBSpec
    extends TestKit(ActorSystem("AbstractDBSpec", config))
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with CancelAfterFailure {

  implicit private val scheduler: Scheduler            = Scheduler.global
  implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped

  @SuppressWarnings(Array("NullAssignment"))
  private var db: JdbcBackend.Database = null

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    db = AbstractDBSpec.beforeAll
    ()
  }

  override protected def afterAll(): Unit = {
    AbstractDBSpec.afterAll(db)
    val cacheDirectory = new Directory(new File(config.getString("akka.cluster.distributed-data.durable.lmdb.dir")))
    if (cacheDirectory.exists) cacheDirectory.deleteRecursively()
    super.afterAll()
  }

}

object AbstractDBSpec {
  def config: Config = ConfigFactory
    .parseString(
      s"""test-instance = "${UUID.randomUUID()}""""
    )
    .withFallback(
      ConfigFactory.parseResources("akka-persistence-test.conf")
    )
    .withFallback(
      ConfigFactory.parseResources("akka-test.conf")
    )
    .withFallback(
      ConfigFactory.load()
    )
    .resolve()

  def beforeAll(implicit
      system: ActorSystem,
      typedSystem: typed.ActorSystem[Nothing],
      sc: Scheduler
  ): JdbcBackend.Database = {
    val database = Task
      .fromFuture {
        val db = Database.forConfig("h2.db", system.settings.config)
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
        }.as(db)
      }
      .runSyncUnsafe(30.seconds)
    val cluster  = Cluster(typedSystem)
    cluster.manager ! Join(cluster.selfMember.address)
    database
  }

  def afterAll(db: JdbcBackend.Database): Unit =
    db.close()

}
